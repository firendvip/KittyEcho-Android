from __future__ import annotations

import math
import re
from collections.abc import Mapping, Sequence
from typing import Any

from .contracts import ContractError, validate_reference_set
from .metrics import edit_stats
from .normalization import NormalizationConfig, normalize_text


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_RUN_ID = re.compile(r"^run_[0-9a-f]{12}$")
_DATASET_ID = re.compile(r"^dataset_[0-9a-f]{12}$")
_MODEL_ALIAS = re.compile(r"^M[0-9]{3}$")
_CLIP_ID = re.compile(r"^clip_[0-9a-f]{12}$")

_OUTPUT_KEYS = {
    "schema_version",
    "run_id",
    "dataset_id",
    "model_alias",
    "decoder_contract_id",
    "pcm_contract_id",
    "input_transform_id",
    "snapshot_fingerprint_sha256",
    "development_decoder_plan_sha256",
    "reference_accessed",
    "predictions",
    "eligibility",
}
_PREDICTION_KEYS = {
    "clip_id",
    "pcm_sha256",
    "pcm_payload_sha256",
    "hypothesis",
    "status",
    "error_code",
    "stop_to_final_ns",
}
_ELIGIBILITY = {
    "development_only": True,
    "emulator_only": True,
    "formal_eligible": False,
    "product_decision_eligible": False,
    "production_eligible": False,
}
_SCORE_KEYS = {
    "schema_version",
    "run_id",
    "dataset_id",
    "model_alias",
    "snapshot_fingerprint_sha256",
    "development_decoder_plan_sha256",
    "normalization",
    "metrics",
    "clip_scores",
    "eligibility",
}
_SCORE_METRIC_KEYS = {
    "cer",
    "substitutions",
    "deletions",
    "insertions",
    "reference_characters",
    "sentence_count",
    "sentence_exact_rate",
    "status_counts",
}
_CLIP_SCORE_KEYS = {
    "clip_id",
    "cer",
    "substitutions",
    "deletions",
    "insertions",
    "reference_characters",
    "exact",
    "status",
    "error_code",
    "stop_to_final_ns",
}


def validate_development_emulator_output(
    data: object,
    *,
    expected_dataset_id: str,
    expected_snapshot_fingerprint_sha256: str,
    expected_development_decoder_plan_sha256: str,
    expected_clip_ids: Sequence[str],
) -> dict[str, Any]:
    """Validate the isolated dev96 emulator output without formal authority."""
    output = _mapping(data, "development emulator output")
    _exact_keys(output, _OUTPUT_KEYS, "development emulator output")
    _require_sha256(
        expected_snapshot_fingerprint_sha256,
        "expected development snapshot fingerprint",
    )
    _require_sha256(
        expected_development_decoder_plan_sha256,
        "expected development decoder plan",
    )
    if not _DATASET_ID.fullmatch(expected_dataset_id):
        raise ContractError("expected development dataset_id is invalid")
    frozen_clip_ids = list(expected_clip_ids)
    if (
        len(frozen_clip_ids) != 96
        or len(set(frozen_clip_ids)) != 96
        or any(not _CLIP_ID.fullmatch(value) for value in frozen_clip_ids)
    ):
        raise ContractError("expected development clip order is invalid")
    if (
        output["schema_version"] != "development-emulator-model-output-v1"
        or output["decoder_contract_id"] != "first-layer-raw-v1"
        or output["pcm_contract_id"] != "pcm16k-mono-s16le-v1"
        or output["input_transform_id"] != "canonical-pcm-direct-v1"
        or output["reference_accessed"] is not False
    ):
        raise ContractError("development emulator output contracts are invalid")
    if not _RUN_ID.fullmatch(_string(output["run_id"], "run_id")):
        raise ContractError("development emulator run_id is invalid")
    if not _MODEL_ALIAS.fullmatch(_string(output["model_alias"], "model_alias")):
        raise ContractError("development emulator model alias is invalid")
    if output["dataset_id"] != expected_dataset_id:
        raise ContractError("development emulator dataset differs from host commitment")
    snapshot = _require_sha256(
        output["snapshot_fingerprint_sha256"],
        "development snapshot fingerprint",
    )
    plan_sha256 = _require_sha256(
        output["development_decoder_plan_sha256"],
        "development decoder plan",
    )
    if (
        snapshot != expected_snapshot_fingerprint_sha256
        or plan_sha256 != expected_development_decoder_plan_sha256
    ):
        raise ContractError("development emulator output differs from host commitments")
    eligibility = _mapping(output["eligibility"], "development eligibility")
    if eligibility != _ELIGIBILITY or set(eligibility) != set(_ELIGIBILITY):
        raise ContractError("development emulator eligibility is invalid")
    raw_predictions = output["predictions"]
    if not isinstance(raw_predictions, list) or len(raw_predictions) != 96:
        raise ContractError("development emulator output must contain exactly 96 predictions")
    predictions: list[dict[str, Any]] = []
    for index, raw_prediction in enumerate(raw_predictions):
        context = f"development prediction[{index}]"
        prediction = _mapping(raw_prediction, context)
        _exact_keys(prediction, _PREDICTION_KEYS, context)
        clip_id = _string(prediction["clip_id"], f"{context}.clip_id")
        if not _CLIP_ID.fullmatch(clip_id):
            raise ContractError(f"{context}.clip_id is invalid")
        _require_sha256(prediction["pcm_sha256"], f"{context}.pcm_sha256")
        _require_sha256(
            prediction["pcm_payload_sha256"],
            f"{context}.pcm_payload_sha256",
        )
        hypothesis = prediction["hypothesis"]
        if not isinstance(hypothesis, str) or len(hypothesis) > 100_000:
            raise ContractError(f"{context}.hypothesis is invalid")
        status = prediction["status"]
        error_code = prediction["error_code"]
        if status not in {"ok", "error"}:
            raise ContractError(f"{context}.status is invalid")
        if error_code not in {None, "oom", "crash", "decode_error"}:
            raise ContractError(f"{context}.error_code is invalid")
        if (status == "ok") != (error_code is None):
            raise ContractError(f"{context} status and error_code conflict")
        latency = prediction["stop_to_final_ns"]
        if isinstance(latency, bool) or not isinstance(latency, int) or latency < 0:
            raise ContractError(f"{context}.stop_to_final_ns is invalid")
        predictions.append(dict(prediction))
    if [item["clip_id"] for item in predictions] != frozen_clip_ids:
        raise ContractError("development emulator prediction order differs from host plan")
    validated = dict(output)
    validated["predictions"] = predictions
    validated["eligibility"] = dict(eligibility)
    return validated


def score_development_emulator_output(
    data: object,
    reference_set: Mapping[str, Any],
    normalization: NormalizationConfig,
    *,
    expected_dataset_id: str,
    expected_snapshot_fingerprint_sha256: str,
    expected_development_decoder_plan_sha256: str,
    expected_clip_ids: Sequence[str],
) -> dict[str, Any]:
    """Score dev96 output without creating or accepting formal authority."""
    output = validate_development_emulator_output(
        data,
        expected_dataset_id=expected_dataset_id,
        expected_snapshot_fingerprint_sha256=expected_snapshot_fingerprint_sha256,
        expected_development_decoder_plan_sha256=(
            expected_development_decoder_plan_sha256
        ),
        expected_clip_ids=expected_clip_ids,
    )
    if not isinstance(normalization, NormalizationConfig):
        raise ContractError("development normalization config is invalid")
    references = validate_reference_set(
        reference_set,
        expected_dataset_id=expected_dataset_id,
        expected_clip_ids=set(expected_clip_ids),
    )
    references_by_clip = {
        item["clip_id"]: item["text"] for item in references["references"]
    }
    clip_scores: list[dict[str, Any]] = []
    for prediction in output["predictions"]:
        reference = normalize_text(
            references_by_clip[prediction["clip_id"]],
            normalization,
        )
        hypothesis = normalize_text(prediction["hypothesis"], normalization)
        stats = edit_stats(reference, hypothesis)
        clip_scores.append(
            {
                "clip_id": prediction["clip_id"],
                "cer": stats.cer,
                "substitutions": stats.substitutions,
                "deletions": stats.deletions,
                "insertions": stats.insertions,
                "reference_characters": stats.reference_characters,
                "exact": reference == hypothesis,
                "status": prediction["status"],
                "error_code": prediction["error_code"],
                "stop_to_final_ns": prediction["stop_to_final_ns"],
            }
        )
    substitutions = sum(item["substitutions"] for item in clip_scores)
    deletions = sum(item["deletions"] for item in clip_scores)
    insertions = sum(item["insertions"] for item in clip_scores)
    reference_characters = sum(
        item["reference_characters"] for item in clip_scores
    )
    edits = substitutions + deletions + insertions
    report = {
        "schema_version": "development-emulator-score-v1",
        "run_id": output["run_id"],
        "dataset_id": output["dataset_id"],
        "model_alias": output["model_alias"],
        "snapshot_fingerprint_sha256": output["snapshot_fingerprint_sha256"],
        "development_decoder_plan_sha256": output[
            "development_decoder_plan_sha256"
        ],
        "normalization": {
            "profile_id": normalization.profile_id,
            "config": normalization.to_dict(),
            "sha256": normalization.fingerprint(),
        },
        "metrics": {
            "cer": (
                edits / reference_characters
                if reference_characters
                else (0.0 if edits == 0 else float(edits))
            ),
            "substitutions": substitutions,
            "deletions": deletions,
            "insertions": insertions,
            "reference_characters": reference_characters,
            "sentence_count": len(clip_scores),
            "sentence_exact_rate": (
                sum(item["exact"] for item in clip_scores) / len(clip_scores)
            ),
            "status_counts": {
                "ok": sum(item["status"] == "ok" for item in clip_scores),
                "error": sum(
                    item["status"] == "error" for item in clip_scores
                ),
            },
        },
        "clip_scores": clip_scores,
        "eligibility": dict(_ELIGIBILITY),
    }
    return validate_development_emulator_score(
        report,
        expected_dataset_id=expected_dataset_id,
        expected_snapshot_fingerprint_sha256=(
            expected_snapshot_fingerprint_sha256
        ),
        expected_development_decoder_plan_sha256=(
            expected_development_decoder_plan_sha256
        ),
        expected_clip_ids=expected_clip_ids,
    )


def validate_development_emulator_score(
    data: object,
    *,
    expected_dataset_id: str,
    expected_snapshot_fingerprint_sha256: str,
    expected_development_decoder_plan_sha256: str,
    expected_clip_ids: Sequence[str],
) -> dict[str, Any]:
    """Validate a score report that is permanently development-ineligible."""
    report = _mapping(data, "development emulator score")
    _exact_keys(report, _SCORE_KEYS, "development emulator score")
    if report["schema_version"] != "development-emulator-score-v1":
        raise ContractError("development emulator score schema is invalid")
    if not _RUN_ID.fullmatch(_string(report["run_id"], "score run_id")):
        raise ContractError("development emulator score run_id is invalid")
    if not _MODEL_ALIAS.fullmatch(_string(report["model_alias"], "score model_alias")):
        raise ContractError("development emulator score model_alias is invalid")
    if report["dataset_id"] != expected_dataset_id:
        raise ContractError("development emulator score dataset differs from host commitment")
    if not _DATASET_ID.fullmatch(_string(report["dataset_id"], "score dataset_id")):
        raise ContractError("development emulator score dataset_id is invalid")
    snapshot = _require_sha256(
        report["snapshot_fingerprint_sha256"], "score snapshot fingerprint"
    )
    plan_sha256 = _require_sha256(
        report["development_decoder_plan_sha256"], "score development decoder plan"
    )
    if (
        snapshot != expected_snapshot_fingerprint_sha256
        or plan_sha256 != expected_development_decoder_plan_sha256
    ):
        raise ContractError("development emulator score differs from host commitments")
    eligibility = _mapping(report["eligibility"], "development score eligibility")
    if eligibility != _ELIGIBILITY or set(eligibility) != set(_ELIGIBILITY):
        raise ContractError("development emulator score eligibility is invalid")

    normalization_block = _mapping(
        report["normalization"], "development score normalization"
    )
    _exact_keys(
        normalization_block,
        {"profile_id", "config", "sha256"},
        "development score normalization",
    )
    try:
        normalization = NormalizationConfig.from_dict(
            _mapping(normalization_block["config"], "development normalization config")
        )
    except (KeyError, TypeError, ValueError) as error:
        raise ContractError("development normalization config is invalid") from error
    if (
        normalization_block["profile_id"] != normalization.profile_id
        or normalization_block["sha256"] != normalization.fingerprint()
    ):
        raise ContractError("development score normalization is inconsistent")

    frozen_clip_ids = list(expected_clip_ids)
    if (
        len(frozen_clip_ids) != 96
        or len(set(frozen_clip_ids)) != 96
        or any(not _CLIP_ID.fullmatch(value) for value in frozen_clip_ids)
    ):
        raise ContractError("expected development score clip order is invalid")
    raw_clip_scores = report["clip_scores"]
    if not isinstance(raw_clip_scores, list) or len(raw_clip_scores) != 96:
        raise ContractError("development emulator score must contain 96 clip scores")
    clip_scores: list[dict[str, Any]] = []
    for index, raw_score in enumerate(raw_clip_scores):
        context = f"development clip score[{index}]"
        score = _mapping(raw_score, context)
        _exact_keys(score, _CLIP_SCORE_KEYS, context)
        clip_id = _string(score["clip_id"], f"{context}.clip_id")
        if not _CLIP_ID.fullmatch(clip_id):
            raise ContractError(f"{context}.clip_id is invalid")
        for field in (
            "substitutions",
            "deletions",
            "insertions",
            "reference_characters",
            "stop_to_final_ns",
        ):
            _nonnegative_int(score[field], f"{context}.{field}")
        expected_cer = _cer(
            score["substitutions"] + score["deletions"] + score["insertions"],
            score["reference_characters"],
        )
        if not math.isclose(
            _nonnegative_number(score["cer"], f"{context}.cer"),
            expected_cer,
            rel_tol=0,
            abs_tol=1e-12,
        ):
            raise ContractError(f"{context}.cer is internally inconsistent")
        if not isinstance(score["exact"], bool):
            raise ContractError(f"{context}.exact must be boolean")
        if score["exact"] != (
            score["substitutions"] == 0
            and score["deletions"] == 0
            and score["insertions"] == 0
        ):
            raise ContractError(f"{context}.exact is internally inconsistent")
        if score["status"] not in {"ok", "error"}:
            raise ContractError(f"{context}.status is invalid")
        if score["error_code"] not in {None, "oom", "crash", "decode_error"}:
            raise ContractError(f"{context}.error_code is invalid")
        if (score["status"] == "ok") != (score["error_code"] is None):
            raise ContractError(f"{context} status and error_code conflict")
        clip_scores.append(dict(score))
    if [item["clip_id"] for item in clip_scores] != frozen_clip_ids:
        raise ContractError("development score clip order differs from host plan")

    metrics = _mapping(report["metrics"], "development score metrics")
    _exact_keys(metrics, _SCORE_METRIC_KEYS, "development score metrics")
    expected_counts = {
        field: sum(item[field] for item in clip_scores)
        for field in (
            "substitutions",
            "deletions",
            "insertions",
            "reference_characters",
        )
    }
    for field, expected in expected_counts.items():
        if _nonnegative_int(metrics[field], f"development metrics.{field}") != expected:
            raise ContractError(f"development metrics.{field} is internally inconsistent")
    if metrics["sentence_count"] != len(clip_scores):
        raise ContractError("development metrics.sentence_count is internally inconsistent")
    expected_cer = _cer(
        expected_counts["substitutions"]
        + expected_counts["deletions"]
        + expected_counts["insertions"],
        expected_counts["reference_characters"],
    )
    if not math.isclose(
        _nonnegative_number(metrics["cer"], "development metrics.cer"),
        expected_cer,
        rel_tol=0,
        abs_tol=1e-12,
    ):
        raise ContractError("development metrics.cer is internally inconsistent")
    expected_exact_rate = sum(item["exact"] for item in clip_scores) / len(clip_scores)
    if not math.isclose(
        _nonnegative_number(
            metrics["sentence_exact_rate"],
            "development metrics.sentence_exact_rate",
        ),
        expected_exact_rate,
        rel_tol=0,
        abs_tol=1e-12,
    ):
        raise ContractError(
            "development metrics.sentence_exact_rate is internally inconsistent"
        )
    status_counts = _mapping(
        metrics["status_counts"], "development metrics.status_counts"
    )
    if status_counts != {
        "ok": sum(item["status"] == "ok" for item in clip_scores),
        "error": sum(item["status"] == "error" for item in clip_scores),
    }:
        raise ContractError("development metrics.status_counts is internally inconsistent")

    validated = dict(report)
    validated["normalization"] = dict(normalization_block)
    validated["metrics"] = dict(metrics)
    validated["clip_scores"] = clip_scores
    validated["eligibility"] = dict(eligibility)
    return validated


def _mapping(value: object, context: str) -> dict[str, Any]:
    if not isinstance(value, Mapping) or any(not isinstance(key, str) for key in value):
        raise ContractError(f"{context} must be an object with string keys")
    return dict(value)


def _exact_keys(value: Mapping[str, Any], expected: set[str], context: str) -> None:
    if set(value) != expected:
        raise ContractError(f"{context} fields are invalid")


def _string(value: object, context: str) -> str:
    if not isinstance(value, str) or not value:
        raise ContractError(f"{context} must be a non-empty string")
    return value


def _require_sha256(value: object, context: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ContractError(f"{context} must be a lowercase sha256")
    return value


def _nonnegative_int(value: object, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ContractError(f"{context} must be a non-negative integer")
    return value


def _nonnegative_number(value: object, context: str) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
        or value < 0
    ):
        raise ContractError(f"{context} must be a finite non-negative number")
    return float(value)


def _cer(edits: int, reference_characters: int) -> float:
    if reference_characters == 0:
        return 0.0 if edits == 0 else float(edits)
    return edits / reference_characters
