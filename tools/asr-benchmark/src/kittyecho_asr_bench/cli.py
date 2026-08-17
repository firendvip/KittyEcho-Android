from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Callable, Mapping, Optional, Sequence

from .artifacts import (
    commit_json_artifact,
    load_committed_json_artifact,
    validate_private_artifact_input_path,
    validate_private_artifact_output_path,
)
from .blinding import build_decoder_plan, create_blind_bundle
from .contracts import (
    ContractError,
    VerifiedExplorationEvidence,
    _revalidate_formal_snapshot_bindings,
    benchmark_evidence_sha256,
    canonical_sha256,
    internal_evaluation_download_blockers,
    load_json,
    production_distribution_blockers,
    verified_dataset_document,
    verify_dataset_evidence,
    verify_exploration_evidence,
    validate_dataset_protocol,
    validate_model_output,
    validate_model_registry,
    validate_private_input_path,
    validate_public_plan,
    validate_recording_manifest,
    validate_reference_set,
)
from .metrics import score_model
from .engineering import build_engineering_report
from .normalization import NormalizationConfig
from .pcm import inspect_pcm16_wav, validate_manifest_pcm
from .reveal import reveal_winner
from .selection import SelectionConfig, select_winner


def _write_json_exclusive(
    path: Path,
    value: Any,
    *,
    private: bool = False,
    precommit_validate: Optional[Callable[[], None]] = None,
    postcommit_validate: Optional[Callable[[], None]] = None,
    artifact_kind: str = "generic-json",
    run_id: Optional[str] = None,
    evidence_sha256: Optional[str] = None,
    plan_sha256: Optional[str] = None,
) -> dict[str, Any]:
    """Compatibility wrapper for the non-destructive receipt transaction."""
    del private
    if not isinstance(value, Mapping):
        # Preserve the old serializer's fail-closed error for non-JSON values.
        json.dumps(value, allow_nan=False)
        raise ContractError("artifact payload must be a JSON object")
    resolved_run_id = run_id or value.get("run_id") or "run_000000000000"
    resolved_evidence_sha256 = (
        evidence_sha256
        or canonical_sha256(
            {
                "binding": "generic-untrusted-json",
                "payload_sha256": canonical_sha256(value),
            }
        )
    )
    resolved_plan_sha256 = plan_sha256 or canonical_sha256(value)
    validation_count = 0

    def validate() -> None:
        nonlocal validation_count
        validation_count += 1
        if validation_count == 1:
            if precommit_validate is not None:
                precommit_validate()
        else:
            callback = postcommit_validate or precommit_validate
            if callback is not None:
                callback()

    return commit_json_artifact(
        path,
        value,
        artifact_kind=artifact_kind,
        run_id=resolved_run_id,
        evidence_sha256=resolved_evidence_sha256,
        plan_sha256=resolved_plan_sha256,
        validate_evidence=validate,
    )


def _formal_commit_validator(
    manifest: Any,
    profile: str,
    exploration_evidence: Optional[VerifiedExplorationEvidence],
) -> Optional[Callable[[], None]]:
    if profile not in {"exploration", "production_confirmation"}:
        return None

    def validate() -> None:
        _revalidate_formal_snapshot_bindings(
            manifest,
            profile,
            exploration_evidence=exploration_evidence,
        )

    return validate


def _artifact_evidence_sha256(
    manifest: Any,
    profile: str,
    exploration_evidence: Optional[VerifiedExplorationEvidence],
) -> str:
    return benchmark_evidence_sha256(
        manifest,
        profile,
        exploration_evidence=exploration_evidence,
    )


def _load_public_plan_artifact(
    path: Path,
    *,
    expected_evidence_sha256: Optional[str] = None,
) -> tuple[dict[str, Any], Optional[dict[str, Any]]]:
    raw = load_json(path)
    if (
        isinstance(raw, Mapping)
        and raw.get("receipt_type") == "kittyecho-asr-json-artifact-v1"
    ):
        plan, receipt = load_committed_json_artifact(
            path,
            expected_kind="blind-public-plan",
            expected_evidence_sha256=expected_evidence_sha256,
        )
        plan = validate_public_plan(plan)
        if receipt["plan_sha256"] != canonical_sha256(plan):
            raise ContractError(
                "public plan receipt does not bind the canonical public plan"
            )
        return plan, receipt
    plan = validate_public_plan(raw)
    if plan["protocol_profile"] in {"exploration", "production_confirmation"}:
        raise ContractError(
            "formal public plan must be consumed through a valid commit receipt"
        )
    return plan, None


def _load_committed_cli_artifact(
    path: Path,
    *,
    artifact_kind: str,
    plan_sha256: str,
    expected_evidence_sha256: Optional[str] = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    return load_committed_json_artifact(
        path,
        expected_kind=artifact_kind,
        expected_evidence_sha256=expected_evidence_sha256,
        expected_plan_sha256=plan_sha256,
    )


def _combine_validators(
    *validators: Optional[Callable[[], None]],
) -> Callable[[], None]:
    active = tuple(validator for validator in validators if validator is not None)

    def validate() -> None:
        for validator in active:
            validator()

    return validate


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="kittyecho-asr-bench")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_manifest = subparsers.add_parser("validate-manifest")
    validate_manifest.add_argument("--manifest", type=Path, required=True)
    validate_manifest.add_argument("--repo-root", type=Path, required=True)
    validate_manifest.add_argument("--fixture-root", type=Path)

    validate_registry = subparsers.add_parser("validate-registry")
    validate_registry.add_argument("--registry", type=Path, required=True)

    model_gate = subparsers.add_parser("model-gate")
    model_gate.add_argument("--registry", type=Path, required=True)
    model_gate.add_argument("--model-id", required=True)
    model_gate.add_argument("--repo-root", type=Path)
    model_gate.add_argument("--risk-acceptance-receipt", type=Path)
    model_gate.add_argument(
        "--purpose",
        choices=(
            "internal_evaluation_download",
            "production_distribution",
        ),
        required=True,
    )

    freeze_artifact = subparsers.add_parser("freeze-model-artifact")
    freeze_artifact.add_argument("--model-id", required=True)
    freeze_artifact.add_argument("--artifact-filename", required=True)
    freeze_artifact.add_argument("--artifact", type=Path, required=True)
    freeze_artifact.add_argument("--receipt", type=Path, required=True)
    freeze_artifact.add_argument("--repo-root", type=Path, required=True)

    inspect = subparsers.add_parser("inspect-pcm")
    inspect.add_argument("path", type=Path)
    inspect.add_argument("--allow-empty", action="store_true")

    blind = subparsers.add_parser("blind")
    blind.add_argument("--manifest", type=Path, required=True)
    blind.add_argument("--repo-root", type=Path, required=True)
    blind.add_argument("--snapshot-root", type=Path)
    blind.add_argument("--fixture-root", type=Path)
    blind.add_argument("--dataset-protocol", type=Path, required=True)
    blind.add_argument("--registry", type=Path, required=True)
    blind.add_argument("--normalization-config", type=Path)
    blind.add_argument("--selection-config", type=Path)
    blind.add_argument("--exploration-manifest", type=Path)
    blind.add_argument("--exploration-public-plan", type=Path)
    blind.add_argument("--exploration-dataset-protocol", type=Path)
    blind.add_argument("--models", required=True)
    blind.add_argument("--seed", type=int, required=True)
    blind.add_argument("--public-plan", type=Path, required=True)
    blind.add_argument("--private-map", type=Path, required=True)
    blind.add_argument(
        "--artifact-freeze-receipt",
        type=Path,
        action="append",
        default=[],
    )
    blind.add_argument(
        "--risk-acceptance-receipt",
        action="append",
        default=[],
        metavar="MODEL_ID=PATH",
    )
    blind.add_argument("--formal-cohort-receipt", type=Path)

    score = subparsers.add_parser("score")
    score.add_argument("--manifest", type=Path, required=True)
    score.add_argument("--references", type=Path, required=True)
    score.add_argument("--model-output", type=Path)
    score.add_argument("--android-attestation-receipt", type=Path)
    score.add_argument("--android-accuracy-receipt", type=Path)
    score.add_argument("--public-plan", type=Path, required=True)
    score.add_argument("--dataset-protocol", type=Path, required=True)
    score.add_argument("--repo-root", type=Path, required=True)
    score.add_argument("--snapshot-root", type=Path)
    score.add_argument("--fixture-root", type=Path)
    score.add_argument("--normalization-config", type=Path)
    score.add_argument("--selection-config", type=Path)
    score.add_argument("--exploration-manifest", type=Path)
    score.add_argument("--exploration-public-plan", type=Path)
    score.add_argument("--exploration-dataset-protocol", type=Path)
    score.add_argument("--report", type=Path, required=True)
    score.add_argument(
        "--artifact-freeze-receipt",
        type=Path,
        action="append",
        default=[],
    )
    score.add_argument(
        "--risk-acceptance-receipt",
        action="append",
        default=[],
        metavar="MODEL_ID=PATH",
    )
    score.add_argument("--formal-cohort-receipt", type=Path)

    select = subparsers.add_parser("select")
    select.add_argument("--reports", type=Path, nargs="+", required=True)
    select.add_argument("--failures", type=Path, nargs="*")
    select.add_argument("--engineering-reports", type=Path, nargs="*")
    select.add_argument("--public-plan", type=Path, required=True)
    select.add_argument("--repo-root", type=Path, required=True)
    select.add_argument("--selection-config", type=Path)
    select.add_argument("--report", type=Path, required=True)
    select.add_argument("--formal-cohort-receipt", type=Path)

    engineering = subparsers.add_parser("sanitize-engineering")
    engineering.add_argument("--manifest", type=Path, required=True)
    engineering.add_argument("--accuracy-output", type=Path, required=True)
    engineering.add_argument("--accuracy-report", type=Path, required=True)
    engineering.add_argument("--engineering-proof", type=Path, required=True)
    engineering.add_argument("--public-plan", type=Path, required=True)
    engineering.add_argument("--dataset-protocol", type=Path, required=True)
    engineering.add_argument("--repo-root", type=Path, required=True)
    engineering.add_argument("--snapshot-root", type=Path)
    engineering.add_argument("--fixture-root", type=Path)
    engineering.add_argument("--exploration-manifest", type=Path)
    engineering.add_argument("--exploration-public-plan", type=Path)
    engineering.add_argument("--exploration-dataset-protocol", type=Path)
    engineering.add_argument("--report", type=Path, required=True)
    engineering.add_argument("--formal-cohort-receipt", type=Path)

    decoder_plan = subparsers.add_parser("make-decoder-plan")
    decoder_plan.add_argument("--manifest", type=Path, required=True)
    decoder_plan.add_argument("--public-plan", type=Path, required=True)
    decoder_plan.add_argument("--model-alias", required=True)
    decoder_plan.add_argument("--repo-root", type=Path, required=True)
    decoder_plan.add_argument("--snapshot-root", type=Path)
    decoder_plan.add_argument("--fixture-root", type=Path)
    decoder_plan.add_argument("--exploration-manifest", type=Path)
    decoder_plan.add_argument("--exploration-public-plan", type=Path)
    decoder_plan.add_argument("--exploration-dataset-protocol", type=Path)
    decoder_plan.add_argument("--plan", type=Path, required=True)
    decoder_plan.add_argument(
        "--artifact-freeze-receipt",
        type=Path,
        action="append",
        default=[],
    )
    decoder_plan.add_argument(
        "--risk-acceptance-receipt",
        action="append",
        default=[],
        metavar="MODEL_ID=PATH",
    )
    decoder_plan.add_argument("--formal-cohort-receipt", type=Path)

    reveal = subparsers.add_parser("reveal")
    reveal.add_argument("--selection", type=Path, required=True)
    reveal.add_argument("--winner-report", type=Path, required=True)
    reveal.add_argument("--private-map", type=Path, required=True)
    reveal.add_argument("--public-plan", type=Path, required=True)
    reveal.add_argument("--registry", type=Path, required=True)
    reveal.add_argument("--repo-root", type=Path, required=True)
    reveal.add_argument("--report", type=Path, required=True)

    verify_android = subparsers.add_parser("verify-android-bundle")
    verify_android.add_argument(
        "--decoder-plan-receipt",
        type=Path,
        required=True,
    )
    verify_android.add_argument("--accuracy-payload", type=Path, required=True)
    verify_android.add_argument("--engineering-payload", type=Path, required=True)
    verify_android.add_argument(
        "--attestation-envelope",
        type=Path,
        required=True,
    )
    verify_android.add_argument("--accuracy-receipt", type=Path, required=True)
    verify_android.add_argument("--engineering-receipt", type=Path, required=True)
    verify_android.add_argument(
        "--verification-receipt",
        type=Path,
        required=True,
    )
    verify_android.add_argument("--repo-root", type=Path, required=True)
    verify_android.add_argument("--host-challenge-hex", required=True)
    verify_android.add_argument(
        "--policy",
        choices=("development", "formal-no-go"),
        required=True,
    )
    verify_android.add_argument(
        "--trusted-root-spki-sha256",
        action="append",
        default=[],
    )
    verify_android.add_argument(
        "--revoked-certificate-serial",
        type=int,
        action="append",
        default=[],
    )
    verify_android.add_argument(
        "--expected-package-name",
        default="com.wordtaker.keyboard.asrbenchmark.runner",
    )
    verify_android.add_argument("--expected-version-code", type=int)

    observer = subparsers.add_parser(
        "verify-synthetic-observer-bundle",
        help=(
            "validate the external-observer contract with synthetic evidence; "
            "never grants formal eligibility"
        ),
    )
    observer.add_argument("--decoder-plan-receipt", type=Path, required=True)
    observer.add_argument("--accuracy-receipt", type=Path, required=True)
    observer.add_argument("--engineering-receipt", type=Path, required=True)
    observer.add_argument(
        "--android-attestation-receipt",
        type=Path,
        required=True,
    )
    observer.add_argument("--attestation-envelope", type=Path, required=True)
    observer.add_argument("--observer-evidence", type=Path, required=True)
    observer.add_argument("--observer-receipt", type=Path, required=True)
    observer.add_argument("--repo-root", type=Path, required=True)
    observer.add_argument("--observer-challenge-hex", required=True)
    observer.add_argument("--host-challenge-hex", required=True)
    observer.add_argument(
        "--trusted-root-spki-sha256",
        action="append",
        required=True,
    )
    observer.add_argument(
        "--revoked-certificate-serial",
        type=int,
        action="append",
        default=[],
    )
    observer.add_argument(
        "--expected-package-name",
        default="com.wordtaker.keyboard.asrbenchmark.runner",
    )
    observer.add_argument(
        "--expected-version-code",
        type=int,
        required=True,
    )
    observer.add_argument("--expected-apk-sha256", required=True)
    observer.add_argument("--expected-signing-cert-sha256", required=True)
    observer.add_argument("--expected-runner-build-sha256", required=True)
    observer.add_argument(
        "--expected-host-clock-boot-id-sha256",
        required=True,
    )
    observer.add_argument(
        "--expected-device-physical-ram-bytes",
        type=int,
        required=True,
    )
    observer.add_argument(
        "--expected-max-process-memory-bytes",
        type=int,
        required=True,
    )
    return parser


def _normalization_config(path: Optional[Path]) -> NormalizationConfig:
    if path is None:
        return NormalizationConfig()
    value = load_json(path, max_bytes=64 * 1024)
    if not isinstance(value, dict):
        raise ContractError("normalization config must be a JSON object")
    expected = {
        "profile_id",
        "unicode_form",
        "whitespace",
        "punctuation",
        "latin_case",
        "numbers",
    }
    if set(value) != expected:
        raise ContractError("normalization config must contain exactly the v1 fields")
    try:
        return NormalizationConfig.from_dict(value)
    except (TypeError, ValueError) as error:
        raise ContractError("normalization config is invalid") from error


def _selection_config(path: Optional[Path]) -> SelectionConfig:
    if path is None:
        return SelectionConfig()
    value = load_json(path, max_bytes=64 * 1024)
    if not isinstance(value, dict):
        raise ContractError("selection config must be a JSON object")
    expected = set(SelectionConfig.__dataclass_fields__)
    if set(value) != expected:
        raise ContractError("selection config must contain exactly the v1 fields")
    try:
        return SelectionConfig(**value)
    except (TypeError, ValueError) as error:
        raise ContractError("selection config is invalid") from error


def _risk_acceptance_receipt_bindings(
    values: Sequence[str],
) -> dict[str, Path]:
    bindings: dict[str, Path] = {}
    for value in values:
        if not isinstance(value, str) or value.count("=") != 1:
            raise ContractError(
                "risk acceptance receipt must use MODEL_ID=PATH"
            )
        model_id, raw_path = value.split("=", 1)
        if not model_id or not raw_path or model_id in bindings:
            raise ContractError(
                "risk acceptance receipt bindings must be unique and non-empty"
            )
        path = Path(raw_path)
        if not path.is_absolute():
            raise ContractError(
                "risk acceptance receipt path must be absolute"
            )
        bindings[model_id] = path
    return bindings


def _trusted_registry_for_formal_path(
    registry_path: Path,
) -> dict[str, Any]:
    from .model_security import (
        load_trusted_model_registry,
        registry_path_is_trusted,
    )

    if not registry_path_is_trusted(registry_path):
        raise ContractError(
            "formal benchmark requires the fixed trusted registry path"
        )
    return load_trusted_model_registry().document


def _verify_formal_cli_model_cohort(
    arguments: argparse.Namespace,
    *,
    profile: str,
    model_ids: Sequence[str],
):
    from .model_security import verify_formal_model_cohort

    if getattr(arguments, "formal_cohort_receipt", None) is None:
        raise ContractError(
            "formal model cohort closure receipt output is required"
        )
    return verify_formal_model_cohort(
        profile=profile,
        model_ids=model_ids,
        artifact_freeze_receipt_paths=tuple(
            getattr(arguments, "artifact_freeze_receipt", ())
        ),
        risk_acceptance_receipt_paths=_risk_acceptance_receipt_bindings(
            getattr(arguments, "risk_acceptance_receipt", ())
        ),
        repo_root=arguments.repo_root,
    )


def _reject_formal_model_inputs_for_development(
    arguments: argparse.Namespace,
) -> None:
    if (
        getattr(arguments, "formal_cohort_receipt", None) is not None
        or getattr(arguments, "artifact_freeze_receipt", ())
        or getattr(arguments, "risk_acceptance_receipt", ())
    ):
        raise ContractError(
            "formal model receipts are not accepted by development commands"
        )


def _require_formal_closure_receipt_argument(
    arguments: argparse.Namespace,
    profile: str,
) -> None:
    receipt_path = getattr(arguments, "formal_cohort_receipt", None)
    if profile in {"exploration", "production_confirmation"}:
        if receipt_path is None:
            raise ContractError(
                "formal model cohort closure receipt required"
            )
    elif receipt_path is not None:
        raise ContractError(
            "development commands cannot consume a formal cohort receipt"
        )


def _validate_formal_closure_receipt(
    arguments: argparse.Namespace,
    *,
    public_plan: Mapping[str, Any],
    evidence_sha256: str,
) -> None:
    profile = public_plan["protocol_profile"]
    _require_formal_closure_receipt_argument(arguments, profile)
    if profile not in {"exploration", "production_confirmation"}:
        return
    from .model_security import load_formal_model_cohort_closure

    load_formal_model_cohort_closure(
        arguments.formal_cohort_receipt,
        public_plan=public_plan,
        benchmark_evidence_sha256=evidence_sha256,
        repo_root=arguments.repo_root,
    )


def _load_exploration_evidence(
    arguments: argparse.Namespace,
    protocol_profile: str,
) -> Optional[VerifiedExplorationEvidence]:
    paths = (
        getattr(arguments, "exploration_manifest", None),
        getattr(arguments, "exploration_public_plan", None),
        getattr(arguments, "exploration_dataset_protocol", None),
    )
    if protocol_profile == "production_confirmation":
        if any(path is None for path in paths):
            raise ContractError(
                "production commands require --exploration-manifest, "
                "--exploration-public-plan, and "
                "--exploration-dataset-protocol"
            )
    elif any(path is not None for path in paths):
        raise ContractError(
            "exploration evidence arguments are allowed only for "
            "production_confirmation"
        )
    else:
        return None

    resolved_paths = (
        validate_private_input_path(paths[0], arguments.repo_root),
        validate_private_artifact_input_path(
            paths[1],
            arguments.repo_root,
        ),
        validate_private_input_path(paths[2], arguments.repo_root),
    )
    snapshot_root = getattr(arguments, "snapshot_root", None)
    if snapshot_root is None:
        raise ContractError(
            "formal commands require an explicit repository-external "
            "--snapshot-root"
        )
    return verify_exploration_evidence(
        resolved_paths[0],
        resolved_paths[1],
        resolved_paths[2],
        arguments.repo_root,
        snapshot_root=snapshot_root,
        fixture_root=arguments.fixture_root,
    )


def _load_dataset_for_protocol(
    arguments: argparse.Namespace,
    protocol: Mapping[str, Any],
) -> tuple[Any, dict[str, Any]]:
    if protocol["profile"] in {"exploration", "production_confirmation"}:
        snapshot_root = getattr(arguments, "snapshot_root", None)
        if snapshot_root is None:
            raise ContractError(
                "formal commands require an explicit repository-external "
                "--snapshot-root"
            )
        evidence = verify_dataset_evidence(
            arguments.manifest,
            arguments.repo_root,
            snapshot_root=snapshot_root,
            fixture_root=arguments.fixture_root,
        )
        return evidence, verified_dataset_document(evidence)
    manifest = validate_recording_manifest(
        load_json(arguments.manifest),
        arguments.repo_root,
    )
    validate_manifest_pcm(
        manifest,
        arguments.repo_root,
        fixture_root=arguments.fixture_root,
    )
    return manifest, manifest


def _validate_blind_model_selection(
    models: Sequence[str],
    registry: dict[str, Any],
    protocol: dict[str, Any],
) -> tuple[list[str], str, str]:
    selected = list(models)
    registry_models = {
        item["model_id"]: item for item in registry["models"]
    }
    if (
        not selected
        or len(selected) != len(set(selected))
        or any(model_id not in registry_models for model_id in selected)
    ):
        raise ContractError(
            "every blind model ID must appear exactly once in the frozen registry"
        )
    baselines = [
        model_id
        for model_id in selected
        if registry_models[model_id]["role"] == "baseline"
    ]
    if len(baselines) != 1:
        raise ContractError("blind run must include exactly one current baseline model")

    profile = protocol["profile"]
    if profile in {"exploration", "production_confirmation"}:
        matching_cohorts = [
            cohort
            for cohort in registry["formal_cohorts"]
            if profile in cohort["profiles"]
        ]
        if len(matching_cohorts) != 1:
            raise ContractError(
                "formal protocol must resolve to exactly one frozen registry cohort"
            )
        cohort = matching_cohorts[0]
        if selected != cohort["model_ids"]:
            raise ContractError(
                "formal blind run models must equal the complete frozen formal cohort"
            )
        return selected, cohort["baseline_model_id"], cohort["cohort_id"]

    return selected, baselines[0], "development_subset"


def _execute(arguments: argparse.Namespace) -> str:
    if arguments.command == "verify-synthetic-observer-bundle":
        from .android_attestation import AndroidAttestationPolicy
        from .external_observer import (
            ExternalObserverPolicy,
            verify_and_commit_synthetic_observer_bundle,
        )

        try:
            observer_challenge = bytes.fromhex(
                arguments.observer_challenge_hex
            )
            host_challenge = bytes.fromhex(arguments.host_challenge_hex)
        except ValueError as error:
            raise ContractError(
                "observer and host challenges must be lowercase hexadecimal"
            ) from error
        if (
            arguments.observer_challenge_hex != observer_challenge.hex()
            or arguments.host_challenge_hex != host_challenge.hex()
        ):
            raise ContractError(
                "observer and host challenges must be lowercase hexadecimal"
            )
        attestation_policy = AndroidAttestationPolicy.formal_no_go(
            trusted_root_spki_sha256=arguments.trusted_root_spki_sha256,
            revoked_certificate_serials=arguments.revoked_certificate_serial,
            expected_package_name=arguments.expected_package_name,
            expected_version_code=arguments.expected_version_code,
            expected_host_challenge=host_challenge,
        )
        policy = ExternalObserverPolicy.synthetic_contract(
            observer_challenge=observer_challenge,
            android_attestation_policy=attestation_policy,
            expected_package_name=arguments.expected_package_name,
            expected_version_code=arguments.expected_version_code,
            expected_apk_sha256=arguments.expected_apk_sha256,
            expected_signing_cert_sha256=(
                arguments.expected_signing_cert_sha256
            ),
            expected_runner_build_sha256=(
                arguments.expected_runner_build_sha256
            ),
            expected_host_clock_boot_id_sha256=(
                arguments.expected_host_clock_boot_id_sha256
            ),
            expected_device_physical_ram_bytes=(
                arguments.expected_device_physical_ram_bytes
            ),
            expected_max_process_memory_bytes=(
                arguments.expected_max_process_memory_bytes
            ),
        )
        result = verify_and_commit_synthetic_observer_bundle(
            decoder_plan_receipt_path=arguments.decoder_plan_receipt,
            accuracy_receipt_path=arguments.accuracy_receipt,
            engineering_receipt_path=arguments.engineering_receipt,
            android_attestation_receipt_path=(
                arguments.android_attestation_receipt
            ),
            attestation_envelope_path=arguments.attestation_envelope,
            observer_evidence_path=arguments.observer_evidence,
            observer_receipt_path=arguments.observer_receipt,
            repo_root=arguments.repo_root,
            policy=policy,
        )
        return (
            "verified synthetic external-observer contract: "
            f"assurance={result.assurance_level}, "
            "formal_eligible=false, product_decision_eligible=false"
        )

    if arguments.command == "verify-android-bundle":
        from .android_attestation import (
            AndroidAttestationPolicy,
            verify_and_commit_android_bundle,
        )

        try:
            host_challenge = bytes.fromhex(arguments.host_challenge_hex)
        except ValueError as error:
            raise ContractError(
                "host attestation challenge must be lowercase hexadecimal"
            ) from error
        if arguments.host_challenge_hex != host_challenge.hex():
            raise ContractError(
                "host attestation challenge must be lowercase hexadecimal"
            )
        if arguments.policy == "development":
            if (
                arguments.trusted_root_spki_sha256
                or arguments.revoked_certificate_serial
            ):
                raise ContractError(
                    "development policy does not accept formal trust inputs"
                )
            policy = AndroidAttestationPolicy.development(host_challenge)
        else:
            policy = AndroidAttestationPolicy.formal_no_go(
                trusted_root_spki_sha256=arguments.trusted_root_spki_sha256,
                revoked_certificate_serials=arguments.revoked_certificate_serial,
                expected_package_name=arguments.expected_package_name,
                expected_version_code=arguments.expected_version_code,
                expected_host_challenge=host_challenge,
            )
        result = verify_and_commit_android_bundle(
            decoder_plan_receipt_path=arguments.decoder_plan_receipt,
            accuracy_payload_path=arguments.accuracy_payload,
            engineering_payload_path=arguments.engineering_payload,
            attestation_envelope_path=arguments.attestation_envelope,
            accuracy_receipt_path=arguments.accuracy_receipt,
            engineering_receipt_path=arguments.engineering_receipt,
            attestation_receipt_path=arguments.verification_receipt,
            repo_root=arguments.repo_root,
            policy=policy,
        )
        return (
            "verified Android bundle: "
            f"assurance={result.assurance_level}, "
            "formal_eligible=false, product_decision_eligible=false"
        )

    if arguments.command == "validate-manifest":
        manifest = validate_recording_manifest(load_json(arguments.manifest), arguments.repo_root)
        validate_manifest_pcm(
            manifest,
            arguments.repo_root,
            fixture_root=arguments.fixture_root,
        )
        return f"valid: {len(manifest['clips'])} clips"

    if arguments.command == "validate-registry":
        registry = validate_model_registry(load_json(arguments.registry))
        return f"valid: {len(registry['models'])} models"

    if arguments.command == "model-gate":
        from .model_security import (
            load_trusted_model_registry,
            registry_path_is_trusted,
        )

        trusted_registry_path = registry_path_is_trusted(arguments.registry)
        if trusted_registry_path:
            registry = load_trusted_model_registry().document
        else:
            registry = validate_model_registry(load_json(arguments.registry))
        if arguments.purpose == "internal_evaluation_download":
            blockers = internal_evaluation_download_blockers(
                registry,
                arguments.model_id,
                risk_acceptance_receipt_path=(
                    arguments.risk_acceptance_receipt
                ),
                repo_root=arguments.repo_root,
            )
        else:
            blockers = production_distribution_blockers(
                registry,
                arguments.model_id,
            )
        if not trusted_registry_path:
            blockers = list(
                dict.fromkeys(["untrusted_registry_snapshot", *blockers])
            )
        return json.dumps(
            {
                "model_id": arguments.model_id,
                "purpose": arguments.purpose,
                "status": "NO_GO" if blockers else "GO",
                "blockers": blockers,
            },
            ensure_ascii=False,
            sort_keys=True,
        )

    if arguments.command == "freeze-model-artifact":
        from .model_security import freeze_model_artifact

        freeze_model_artifact(
            model_id=arguments.model_id,
            artifact_filename=arguments.artifact_filename,
            artifact_path=arguments.artifact,
            receipt_path=arguments.receipt,
            repo_root=arguments.repo_root,
        )
        return (
            f"frozen {arguments.model_id}/"
            f"{arguments.artifact_filename} from safe FD"
        )

    if arguments.command == "inspect-pcm":
        info = inspect_pcm16_wav(arguments.path, allow_empty=arguments.allow_empty)
        return json.dumps(
            {
                "sample_rate_hz": info.sample_rate_hz,
                "channels": info.channels,
                "sample_width_bits": info.sample_width_bits,
                "frame_count": info.frame_count,
                "duration_seconds": info.duration_seconds,
                "sha256": info.sha256,
                "payload_sha256": info.payload_sha256,
                "payload_bytes": info.payload_bytes,
            },
            sort_keys=True,
        )

    if arguments.command == "blind":
        protocol_document = validate_dataset_protocol(
            load_json(arguments.dataset_protocol)
        )
        _require_formal_closure_receipt_argument(
            arguments,
            protocol_document["profile"],
        )
        profile = protocol_document["profile"]
        models = [
            item.strip()
            for item in arguments.models.split(",")
            if item.strip()
        ]
        formal_model_evidence = None
        formal_model_commitments = None
        if profile in {"exploration", "production_confirmation"}:
            registry = _trusted_registry_for_formal_path(
                arguments.registry
            )
        else:
            registry = validate_model_registry(load_json(arguments.registry))
            _reject_formal_model_inputs_for_development(arguments)
        models, baseline_model_id, cohort_id = (
            _validate_blind_model_selection(
                models,
                registry,
                protocol_document,
            )
        )
        if profile in {"exploration", "production_confirmation"}:
            formal_model_evidence = _verify_formal_cli_model_cohort(
                arguments,
                profile=profile,
                model_ids=models,
            )
            from .model_security import formal_model_cohort_commitments

            formal_model_commitments = formal_model_cohort_commitments(
                formal_model_evidence
            )
        exploration_evidence = _load_exploration_evidence(
            arguments,
            profile,
        )
        manifest_input, manifest = _load_dataset_for_protocol(
            arguments,
            protocol_document,
        )
        protocol = validate_dataset_protocol(
            protocol_document,
            manifest_input,
            exploration_evidence=exploration_evidence,
        )
        normalization = _normalization_config(arguments.normalization_config)
        selection_config = _selection_config(arguments.selection_config)
        fingerprints = {
            "manifest_sha256": canonical_sha256(manifest),
            "dataset_protocol_sha256": canonical_sha256(protocol),
            "normalization_sha256": normalization.fingerprint(),
            "selection_config_sha256": canonical_sha256(selection_config.to_dict()),
            "registry_sha256": canonical_sha256(registry),
        }
        private_path = validate_private_artifact_output_path(
            arguments.private_map,
            arguments.repo_root,
        )
        public_plan, private_map = create_blind_bundle(
            manifest_input,
            models,
            seed=arguments.seed,
            contract_fingerprints=fingerprints,
            baseline_model_id=baseline_model_id,
            protocol_profile=protocol["profile"],
            cohort_id=cohort_id,
            exploration_evidence=exploration_evidence,
            formal_model_commitments=formal_model_commitments,
        )
        dataset_commit_validator = _formal_commit_validator(
            manifest_input,
            protocol["profile"],
            exploration_evidence,
        )
        model_commit_validator = None
        if formal_model_evidence is not None:
            from .model_security import formal_model_cohort_commitments

            def validate_formal_model_inputs() -> None:
                if (
                    formal_model_cohort_commitments(
                        formal_model_evidence
                    )
                    != formal_model_commitments
                ):
                    raise ContractError(
                        "formal model cohort changed before plan commit"
                    )

            model_commit_validator = validate_formal_model_inputs
        commit_validator = _combine_validators(
            dataset_commit_validator,
            model_commit_validator,
        )
        evidence_sha256 = _artifact_evidence_sha256(
            manifest_input,
            protocol["profile"],
            exploration_evidence,
        )
        plan_sha256 = canonical_sha256(public_plan)
        if formal_model_evidence is not None:
            from .model_security import (
                commit_formal_model_cohort_closure,
            )

            commit_formal_model_cohort_closure(
                formal_model_evidence,
                public_plan=public_plan,
                benchmark_evidence_sha256=evidence_sha256,
                receipt_path=arguments.formal_cohort_receipt,
                repo_root=arguments.repo_root,
            )
        _write_json_exclusive(
            arguments.public_plan,
            public_plan,
            precommit_validate=commit_validator,
            postcommit_validate=commit_validator,
            artifact_kind="blind-public-plan",
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
        )
        _write_json_exclusive(
            private_path,
            private_map,
            private=True,
            precommit_validate=commit_validator,
            postcommit_validate=commit_validator,
            artifact_kind="blind-private-map",
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
        )
        return f"created blind run {public_plan['run_id']}"

    if arguments.command == "score":
        report_path = validate_private_artifact_output_path(
            arguments.report,
            arguments.repo_root,
        )
        protocol_document = validate_dataset_protocol(
            load_json(arguments.dataset_protocol)
        )
        _require_formal_closure_receipt_argument(
            arguments,
            protocol_document["profile"],
        )
        profile = protocol_document["profile"]
        formal_model_evidence = None
        formal_model_commitments = None
        if profile in {"exploration", "production_confirmation"}:
            from .model_security import (
                formal_model_cohort_commitments,
                load_trusted_model_registry,
            )

            trusted = load_trusted_model_registry()
            cohorts = [
                cohort
                for cohort in trusted.document["formal_cohorts"]
                if profile in cohort["profiles"]
            ]
            if len(cohorts) != 1:
                raise ContractError(
                    "formal score cannot resolve the trusted model cohort"
                )
            formal_model_evidence = _verify_formal_cli_model_cohort(
                arguments,
                profile=profile,
                model_ids=cohorts[0]["model_ids"],
            )
            formal_model_commitments = formal_model_cohort_commitments(
                formal_model_evidence
            )
        else:
            _reject_formal_model_inputs_for_development(arguments)
        exploration_evidence = _load_exploration_evidence(
            arguments,
            profile,
        )
        manifest_input, manifest = _load_dataset_for_protocol(
            arguments,
            protocol_document,
        )
        expected_clip_ids = {clip["clip_id"] for clip in manifest["clips"]}
        reference_path = validate_private_input_path(arguments.references, arguments.repo_root)
        references = validate_reference_set(
            load_json(reference_path),
            expected_dataset_id=manifest["dataset_id"],
            expected_clip_ids=expected_clip_ids,
        )
        evidence_sha256 = _artifact_evidence_sha256(
            manifest_input,
            protocol_document["profile"],
            exploration_evidence,
        )
        raw_public_plan, _public_plan_receipt = _load_public_plan_artifact(
            arguments.public_plan,
            expected_evidence_sha256=evidence_sha256,
        )
        public_plan = validate_public_plan(
            raw_public_plan,
            manifest_input,
            exploration_evidence=exploration_evidence,
        )
        if formal_model_commitments is not None and any(
            public_plan.get(field) != value
            for field, value in formal_model_commitments.items()
        ):
            raise ContractError(
                "formal score model commitments differ from the public plan"
            )
        _validate_formal_closure_receipt(
            arguments,
            public_plan=public_plan,
            evidence_sha256=evidence_sha256,
        )
        plan_sha256 = canonical_sha256(public_plan)
        android_receipts = (
            arguments.android_attestation_receipt,
            arguments.android_accuracy_receipt,
        )
        if any(path is not None for path in android_receipts) and any(
            path is None for path in android_receipts
        ):
            raise ContractError(
                "Android scoring requires both verification and accuracy receipts"
            )
        if all(path is not None for path in android_receipts):
            if arguments.model_output is not None:
                raise ContractError(
                    "scoring cannot mix raw model output with Android receipts"
                )
            from .android_attestation import load_verified_android_accuracy

            output = load_verified_android_accuracy(
                validate_private_artifact_input_path(
                    arguments.android_attestation_receipt,
                    arguments.repo_root,
                ),
                validate_private_artifact_input_path(
                    arguments.android_accuracy_receipt,
                    arguments.repo_root,
                ),
                expected_run_id=public_plan["run_id"],
                expected_evidence_sha256=evidence_sha256,
                expected_plan_sha256=plan_sha256,
            )
        else:
            if protocol_document["profile"] in {
                "exploration",
                "production_confirmation",
            }:
                raise ContractError(
                    "formal scoring requires committed Android attestation and "
                    "accuracy receipts; raw JSON is not eligible"
                )
            if arguments.model_output is None:
                raise ContractError(
                    "development scoring requires --model-output or both "
                    "Android receipt arguments"
                )
            output_path = validate_private_input_path(
                arguments.model_output,
                arguments.repo_root,
            )
            output = load_json(output_path)
        protocol = validate_dataset_protocol(
            protocol_document,
            manifest_input,
            exploration_evidence=exploration_evidence,
        )
        validate_model_output(
            output,
            public_plan,
            manifest_input,
            protocol,
            exploration_evidence=exploration_evidence,
        )
        selection_config = _selection_config(arguments.selection_config)
        report = score_model(
            manifest_input,
            references,
            output,
            _normalization_config(arguments.normalization_config),
            public_plan,
            protocol,
            selection_config.to_dict(),
            exploration_evidence=exploration_evidence,
        )
        commit_validator = _formal_commit_validator(
            manifest_input,
            protocol["profile"],
            exploration_evidence,
        )

        def validate_formal_model_inputs() -> None:
            if formal_model_evidence is None:
                return
            from .model_security import formal_model_cohort_commitments

            if (
                formal_model_cohort_commitments(formal_model_evidence)
                != formal_model_commitments
            ):
                raise ContractError(
                    "formal model cohort changed before score report commit"
                )

        def validate_public_plan_input() -> None:
            current_plan, _current_receipt = _load_public_plan_artifact(
                arguments.public_plan,
                expected_evidence_sha256=evidence_sha256,
            )
            current_plan = validate_public_plan(
                current_plan,
                manifest_input,
                exploration_evidence=exploration_evidence,
            )
            _validate_formal_closure_receipt(
                arguments,
                public_plan=current_plan,
                evidence_sha256=evidence_sha256,
            )
            if canonical_sha256(current_plan) != plan_sha256:
                raise ContractError(
                    "public plan changed before score report commit"
                )

        artifact_validator = _combine_validators(
            commit_validator,
            validate_formal_model_inputs,
            validate_public_plan_input,
        )
        _write_json_exclusive(
            report_path,
            report,
            private=True,
            precommit_validate=artifact_validator,
            postcommit_validate=artifact_validator,
            artifact_kind="score-report",
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
        )
        return f"scored {report['model_alias']}: {len(report['sentence_errors'])} clips"

    if arguments.command == "select":
        report_path = validate_private_artifact_output_path(
            arguments.report,
            arguments.repo_root,
        )
        public_plan, public_plan_receipt = _load_public_plan_artifact(
            arguments.public_plan
        )
        plan_sha256 = canonical_sha256(public_plan)
        _require_formal_closure_receipt_argument(
            arguments,
            public_plan["protocol_profile"],
        )
        formal_evidence_sha256 = (
            public_plan_receipt["evidence_sha256"]
            if public_plan_receipt is not None
            and public_plan["protocol_profile"]
            in {"exploration", "production_confirmation"}
            else None
        )
        if (
            public_plan["protocol_profile"]
            in {"exploration", "production_confirmation"}
            and formal_evidence_sha256 is None
        ):
            raise ContractError(
                "formal selection requires a committed public plan receipt"
            )
        if formal_evidence_sha256 is not None:
            _validate_formal_closure_receipt(
                arguments,
                public_plan=public_plan,
                evidence_sha256=formal_evidence_sha256,
            )
        report_inputs = [
            validate_private_artifact_input_path(path, arguments.repo_root)
            for path in arguments.reports
        ]
        failure_inputs = [
            validate_private_input_path(path, arguments.repo_root)
            for path in (arguments.failures or [])
        ]
        failures = [load_json(path) for path in failure_inputs]
        engineering_inputs = [
            validate_private_artifact_input_path(path, arguments.repo_root)
            for path in (arguments.engineering_reports or [])
        ]
        report_pairs = [
            _load_committed_cli_artifact(
                path,
                artifact_kind="score-report",
                plan_sha256=plan_sha256,
            )
            for path in report_inputs
        ]
        evidence_sha256 = (
            formal_evidence_sha256
            or (
                public_plan_receipt["evidence_sha256"]
                if public_plan_receipt is not None
                else report_pairs[0][1]["evidence_sha256"]
            )
        )
        if any(
            receipt["evidence_sha256"] != evidence_sha256
            for _report, receipt in report_pairs
        ):
            raise ContractError(
                "score report receipts use different evidence fingerprints"
            )
        reports = [report for report, _receipt in report_pairs]
        engineering_pairs = [
            _load_committed_cli_artifact(
                path,
                artifact_kind="engineering-report",
                plan_sha256=plan_sha256,
                expected_evidence_sha256=evidence_sha256,
            )
            for path in engineering_inputs
        ]
        engineering_reports = [
            report for report, _receipt in engineering_pairs
        ]
        result = select_winner(
            reports,
            _selection_config(arguments.selection_config),
            public_plan=public_plan,
            failure_records=failures,
            engineering_reports=engineering_reports,
        )

        def validate_selection_inputs() -> None:
            current_plan, current_plan_receipt = _load_public_plan_artifact(
                arguments.public_plan,
                expected_evidence_sha256=(
                    evidence_sha256
                    if public_plan_receipt is not None
                    else None
                ),
            )
            if canonical_sha256(current_plan) != plan_sha256:
                raise ContractError(
                    "public plan changed before selection report commit"
                )
            _validate_formal_closure_receipt(
                arguments,
                public_plan=current_plan,
                evidence_sha256=evidence_sha256,
            )
            if (
                public_plan_receipt is None
                and current_plan_receipt is not None
            ):
                raise ContractError(
                    "public plan transaction state changed before selection"
                )
            for path in report_inputs:
                _load_committed_cli_artifact(
                    path,
                    artifact_kind="score-report",
                    plan_sha256=plan_sha256,
                    expected_evidence_sha256=evidence_sha256,
                )
            for path in engineering_inputs:
                _load_committed_cli_artifact(
                    path,
                    artifact_kind="engineering-report",
                    plan_sha256=plan_sha256,
                    expected_evidence_sha256=evidence_sha256,
                )

        _write_json_exclusive(
            report_path,
            result,
            private=True,
            precommit_validate=validate_selection_inputs,
            postcommit_validate=validate_selection_inputs,
            artifact_kind="selection-report",
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
        )
        return result["status"]

    if arguments.command == "sanitize-engineering":
        report_path = validate_private_artifact_output_path(
            arguments.report,
            arguments.repo_root,
        )
        protocol_document = validate_dataset_protocol(
            load_json(arguments.dataset_protocol)
        )
        _require_formal_closure_receipt_argument(
            arguments,
            protocol_document["profile"],
        )
        exploration_evidence = _load_exploration_evidence(
            arguments,
            protocol_document["profile"],
        )
        manifest_input, _manifest_document = _load_dataset_for_protocol(
            arguments,
            protocol_document,
        )
        accuracy_output_path = validate_private_input_path(
            arguments.accuracy_output,
            arguments.repo_root,
        )
        accuracy_report_path = validate_private_artifact_input_path(
            arguments.accuracy_report,
            arguments.repo_root,
        )
        proof_path = validate_private_input_path(
            arguments.engineering_proof,
            arguments.repo_root,
        )
        evidence_sha256 = _artifact_evidence_sha256(
            manifest_input,
            protocol_document["profile"],
            exploration_evidence,
        )
        public_plan, _public_plan_receipt = _load_public_plan_artifact(
            arguments.public_plan,
            expected_evidence_sha256=evidence_sha256,
        )
        public_plan = validate_public_plan(
            public_plan,
            manifest_input,
            exploration_evidence=exploration_evidence,
        )
        _validate_formal_closure_receipt(
            arguments,
            public_plan=public_plan,
            evidence_sha256=evidence_sha256,
        )
        plan_sha256 = canonical_sha256(public_plan)
        accuracy_report, _accuracy_receipt = _load_committed_cli_artifact(
            accuracy_report_path,
            artifact_kind="score-report",
            plan_sha256=plan_sha256,
            expected_evidence_sha256=evidence_sha256,
        )
        report = build_engineering_report(
            load_json(proof_path),
            load_json(accuracy_output_path),
            accuracy_report,
            public_plan,
            manifest_input,
            protocol_document,
            exploration_evidence=exploration_evidence,
        )
        commit_validator = _formal_commit_validator(
            manifest_input,
            protocol_document["profile"],
            exploration_evidence,
        )

        def validate_engineering_inputs() -> None:
            current_plan, _current_receipt = _load_public_plan_artifact(
                arguments.public_plan,
                expected_evidence_sha256=evidence_sha256,
            )
            current_plan = validate_public_plan(
                current_plan,
                manifest_input,
                exploration_evidence=exploration_evidence,
            )
            _validate_formal_closure_receipt(
                arguments,
                public_plan=current_plan,
                evidence_sha256=evidence_sha256,
            )
            if canonical_sha256(current_plan) != plan_sha256:
                raise ContractError(
                    "public plan changed before engineering report commit"
                )
            _load_committed_cli_artifact(
                accuracy_report_path,
                artifact_kind="score-report",
                plan_sha256=plan_sha256,
                expected_evidence_sha256=evidence_sha256,
            )

        artifact_validator = _combine_validators(
            commit_validator,
            validate_engineering_inputs,
        )
        _write_json_exclusive(
            report_path,
            report,
            private=True,
            precommit_validate=artifact_validator,
            postcommit_validate=artifact_validator,
            artifact_kind="engineering-report",
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
        )
        return f"sanitized engineering report for {report['model_alias']}"

    if arguments.command == "make-decoder-plan":
        plan_path = validate_private_artifact_output_path(
            arguments.plan,
            arguments.repo_root,
        )
        raw_public_plan, _preliminary_receipt = _load_public_plan_artifact(
            arguments.public_plan
        )
        plan_structure = validate_public_plan(raw_public_plan)
        profile = plan_structure["protocol_profile"]
        formal_model_evidence = None
        if profile in {"exploration", "production_confirmation"}:
            _require_formal_closure_receipt_argument(arguments, profile)
            from .model_security import (
                formal_model_cohort_commitments,
                load_trusted_model_registry,
            )

            trusted = load_trusted_model_registry()
            cohorts = [
                cohort
                for cohort in trusted.document["formal_cohorts"]
                if profile in cohort["profiles"]
            ]
            if len(cohorts) != 1:
                raise ContractError(
                    "formal decoder plan cannot resolve the trusted cohort"
                )
            formal_model_evidence = _verify_formal_cli_model_cohort(
                arguments,
                profile=profile,
                model_ids=cohorts[0]["model_ids"],
            )
            if any(
                plan_structure.get(field) != value
                for field, value in formal_model_cohort_commitments(
                    formal_model_evidence
                ).items()
            ):
                raise ContractError(
                    "formal decoder plan model commitments differ"
                )
        else:
            _reject_formal_model_inputs_for_development(arguments)
        manifest_input, _manifest_document = _load_dataset_for_protocol(
            arguments,
            {"profile": profile},
        )
        exploration_evidence = _load_exploration_evidence(
            arguments,
            profile,
        )
        evidence_sha256 = _artifact_evidence_sha256(
            manifest_input,
            profile,
            exploration_evidence,
        )
        raw_public_plan, _public_plan_receipt = _load_public_plan_artifact(
            arguments.public_plan,
            expected_evidence_sha256=evidence_sha256,
        )
        public_plan = validate_public_plan(
            raw_public_plan,
            manifest_input,
            exploration_evidence=exploration_evidence,
        )
        _validate_formal_closure_receipt(
            arguments,
            public_plan=public_plan,
            evidence_sha256=evidence_sha256,
        )
        plan_sha256 = canonical_sha256(public_plan)
        decoder_plan = build_decoder_plan(
            manifest_input,
            public_plan,
            arguments.model_alias,
            exploration_evidence=exploration_evidence,
        )
        dataset_commit_validator = _formal_commit_validator(
            manifest_input,
            profile,
            exploration_evidence,
        )

        def validate_formal_model_inputs() -> None:
            if formal_model_evidence is None:
                return
            from .model_security import formal_model_cohort_commitments

            if any(
                public_plan.get(field) != value
                for field, value in formal_model_cohort_commitments(
                    formal_model_evidence
                ).items()
            ):
                raise ContractError(
                    "formal model cohort changed before decoder plan commit"
                )

        def validate_decoder_inputs() -> None:
            current_plan, _current_receipt = _load_public_plan_artifact(
                arguments.public_plan,
                expected_evidence_sha256=evidence_sha256,
            )
            current_plan = validate_public_plan(
                current_plan,
                manifest_input,
                exploration_evidence=exploration_evidence,
            )
            _validate_formal_closure_receipt(
                arguments,
                public_plan=current_plan,
                evidence_sha256=evidence_sha256,
            )
            if canonical_sha256(current_plan) != plan_sha256:
                raise ContractError(
                    "public plan changed before decoder plan commit"
                )

        artifact_validator = _combine_validators(
            dataset_commit_validator,
            validate_formal_model_inputs,
            validate_decoder_inputs,
        )
        _write_json_exclusive(
            plan_path,
            decoder_plan,
            private=True,
            precommit_validate=artifact_validator,
            postcommit_validate=artifact_validator,
            artifact_kind="decoder-plan",
            evidence_sha256=evidence_sha256,
            plan_sha256=plan_sha256,
        )
        return f"created decoder plan for {arguments.model_alias}"

    if arguments.command == "reveal":
        # Phase A intentionally does not consume or dereference any reveal
        # artifact. Phase B must add receipt + trusted-attestation validation
        # before this command may inspect its required operator arguments.
        reveal_winner({}, {}, {}, {}, {})
        raise ContractError("unreachable Phase A reveal state")

    raise ContractError("unknown command")


def main(argv: Optional[Sequence[str]] = None) -> int:
    try:
        arguments = _parser().parse_args(argv)
        message = _execute(arguments)
        print(message)
        return 0
    except (ContractError, ValueError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
