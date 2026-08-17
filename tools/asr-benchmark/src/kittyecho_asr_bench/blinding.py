from __future__ import annotations

import hashlib
from typing import Any, Mapping, Sequence

from .contracts import (
    CONTRACT_FINGERPRINT_FIELDS,
    ContractError,
    VerifiedExplorationEvidence,
    _manifest_document_for_artifact,
    _revalidate_formal_snapshot_bindings,
    canonical_sha256,
    decoder_plan_document,
)


class _HashRandom:
    """Small deterministic keyed generator for private blind shuffling."""

    def __init__(self, seed: int) -> None:
        width = max(16, (seed.bit_length() + 7) // 8)
        self._key = hashlib.sha256(seed.to_bytes(width, "big")).digest()
        self._counter = 0

    def _value(self) -> int:
        counter = self._counter.to_bytes(16, "big")
        self._counter += 1
        return int.from_bytes(hashlib.sha256(self._key + counter).digest(), "big")

    def _below(self, upper: int) -> int:
        if upper <= 0:
            raise ValueError("shuffle upper bound must be positive")
        range_size = 1 << 256
        unbiased_limit = range_size - (range_size % upper)
        while True:
            candidate = self._value()
            if candidate < unbiased_limit:
                return candidate % upper

    def shuffle(self, values: list[Any]) -> None:
        for index in range(len(values) - 1, 0, -1):
            swap_index = self._below(index + 1)
            values[index], values[swap_index] = values[swap_index], values[index]


def create_blind_bundle(
    manifest: Any,
    model_ids: Sequence[str],
    *,
    seed: int,
    contract_fingerprints: Mapping[str, str],
    baseline_model_id: str | None = None,
    protocol_profile: str = "development_fixture",
    cohort_id: str = "development_subset",
    exploration_evidence: VerifiedExplorationEvidence | None = None,
    formal_model_commitments: Mapping[str, str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if protocol_profile not in {
        "development_fixture",
        "exploration",
        "production_confirmation",
    }:
        raise ContractError("blind run protocol profile is invalid")
    manifest_document = _manifest_document_for_artifact(
        manifest,
        protocol_profile,
        exploration_evidence=exploration_evidence,
    )
    models = list(model_ids)
    if len(models) != len(set(models)):
        raise ValueError("model_ids contains duplicate entries")
    if len(models) < 2:
        raise ValueError("blind evaluation requires at least two models")
    baseline = baseline_model_id or models[0]
    if baseline not in models:
        raise ValueError("baseline_model_id must be present in model_ids")
    clips = list(manifest_document["clips"])
    if len(clips) < 2:
        raise ValueError("blind evaluation requires at least two clips")
    if (
        isinstance(seed, bool)
        or not isinstance(seed, int)
        or seed < 0
        or seed.bit_length() < 128
    ):
        raise ValueError("seed must be a private, non-negative 128-bit or stronger integer")
    fingerprints = dict(contract_fingerprints)
    if set(fingerprints) != set(CONTRACT_FINGERPRINT_FIELDS):
        raise ContractError("blind run requires every frozen contract fingerprint")
    for field in CONTRACT_FINGERPRINT_FIELDS:
        value = fingerprints[field]
        if (
            not isinstance(value, str)
            or len(value) != 64
            or any(character not in "0123456789abcdef" for character in value)
        ):
            raise ContractError(f"contract fingerprint {field} must be a lowercase sha256")
    if protocol_profile in {"exploration", "production_confirmation"}:
        if cohort_id == "development_subset":
            raise ContractError(
                "formal blind artifacts require a frozen registry cohort"
            )
        if (
            fingerprints["manifest_sha256"]
            != canonical_sha256(manifest_document)
        ):
            raise ContractError(
                "formal blind manifest fingerprint does not match verified evidence"
            )
    commitment_fields = (
        "formal_artifact_set_sha256",
        "formal_risk_acceptance_set_sha256",
        "formal_model_cohort_sha256",
    )
    if formal_model_commitments is None:
        model_commitments: dict[str, str | None] = {
            field: None for field in commitment_fields
        }
    else:
        if set(formal_model_commitments) != set(commitment_fields):
            raise ContractError(
                "formal model commitments must contain exactly the frozen fields"
            )
        model_commitments = dict(formal_model_commitments)
        for field, value in model_commitments.items():
            if (
                not isinstance(value, str)
                or len(value) != 64
                or any(
                    character not in "0123456789abcdef"
                    for character in value
                )
            ):
                raise ContractError(
                    f"formal model commitment {field} must be a lowercase sha256"
                )
    if (
        protocol_profile == "development_fixture"
        and any(value is not None for value in model_commitments.values())
    ):
        raise ContractError(
            "development blind plans cannot carry formal model commitments"
        )

    randomizer = _HashRandom(seed)
    shuffled_models = sorted(models)
    randomizer.shuffle(shuffled_models)
    aliases = [f"M{index:03d}" for index in range(1, len(models) + 1)]
    alias_map = [
        {"model_alias": alias, "model_id": model_id}
        for alias, model_id in zip(aliases, shuffled_models)
    ]
    baseline_alias = next(
        item["model_alias"] for item in alias_map if item["model_id"] == baseline
    )
    assignments = []
    clip_ids = sorted(clip["clip_id"] for clip in clips)
    for alias in aliases:
        order = list(clip_ids)
        randomizer.shuffle(order)
        assignments.append({"model_alias": alias, "clip_order": order})

    run_material = {
        "dataset_id": manifest_document["dataset_id"],
        "model_ids": sorted(models),
        "seed": seed,
        "contract_fingerprints": fingerprints,
    }
    run_id = "run_" + canonical_sha256(run_material)[:12]
    seed_width = max(16, (seed.bit_length() + 7) // 8)
    baseline_nonce = hashlib.sha256(
        seed.to_bytes(seed_width, "big") + b":baseline-commitment-v1"
    ).hexdigest()
    private_map = {
        "schema_version": "1.0",
        "run_id": run_id,
        "seed": seed,
        "alias_map": alias_map,
        "baseline_alias": baseline_alias,
        "baseline_commitment_nonce": baseline_nonce,
        "registry_sha256": fingerprints["registry_sha256"],
    }
    baseline_commitment_sha256 = canonical_sha256(
        {
            "run_id": run_id,
            "baseline_alias": baseline_alias,
            "nonce": baseline_nonce,
        }
    )
    public_plan = {
        "schema_version": "1.0",
        "run_id": run_id,
        "dataset_id": manifest_document["dataset_id"],
        "pcm_contract_id": manifest_document["pcm_contract_id"],
        "protocol_profile": protocol_profile,
        "cohort_id": cohort_id,
        "cohort_model_ids_sha256": canonical_sha256(sorted(models)),
        **model_commitments,
        "assignments": assignments,
        "baseline_commitment_sha256": baseline_commitment_sha256,
        "clip_pcm_sha256": {
            clip["clip_id"]: clip["audio_sha256"]
            for clip in sorted(clips, key=lambda item: item["clip_id"])
        },
        "clip_pcm_payload_sha256": {
            clip["clip_id"]: clip["pcm_payload_sha256"]
            for clip in sorted(clips, key=lambda item: item["clip_id"])
        },
        "randomization": {
            "algorithm": "sha256-fisher-yates-v1",
            "private_map_sha256": canonical_sha256(private_map),
        },
        "contract_fingerprints": fingerprints,
    }
    _revalidate_formal_snapshot_bindings(
        manifest,
        protocol_profile,
        exploration_evidence=exploration_evidence,
    )
    return public_plan, private_map


def build_decoder_plan(
    manifest: Any,
    public_plan: Mapping[str, Any],
    model_alias: str,
    *,
    exploration_evidence: VerifiedExplorationEvidence | None = None,
) -> dict[str, Any]:
    """Create the runner-only plan with answers, slices, and clusters stripped."""
    return decoder_plan_document(
        manifest,
        public_plan,
        model_alias,
        exploration_evidence=exploration_evidence,
    )
