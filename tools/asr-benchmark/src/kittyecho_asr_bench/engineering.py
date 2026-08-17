from __future__ import annotations

import copy
import math
from typing import Any, Mapping, Optional

from .contracts import (
    ContractError,
    VerifiedExplorationEvidence,
    _exact_keys,
    _manifest_document_for_profile,
    _revalidate_formal_snapshot_bindings,
    _require_anonymous_id,
    _require_mapping,
    _require_nonnegative_int,
    _require_nonnegative_number,
    _require_sha256,
    _require_string,
    canonical_sha256,
    validate_dataset_protocol,
    validate_engineering_proof,
    validate_public_plan,
)
from .metrics import validate_score_report


def _percentile(values: list[float], fraction: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def _timing_summary(values: list[float]) -> dict[str, Any]:
    return {
        "count": len(values),
        "p50_ms": _percentile(values, 0.50),
        "p95_ms": _percentile(values, 0.95),
        "max_ms": max(values) if values else None,
    }


def build_engineering_report(
    engineering_proof: Mapping[str, Any],
    accuracy_output: Mapping[str, Any],
    accuracy_report: Mapping[str, Any],
    public_plan: Mapping[str, Any],
    manifest: Any,
    dataset_protocol: Mapping[str, Any],
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
    exploration_manifest: Optional[Mapping[str, Any]] = None,
    exploration_public_plan: Optional[Mapping[str, Any]] = None,
    exploration_dataset_protocol: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    """Sanitize private runner evidence after the accuracy report is frozen."""
    protocol_document = validate_dataset_protocol(dataset_protocol)
    manifest_document = _manifest_document_for_profile(
        manifest,
        protocol_document["profile"],
    )
    plan = validate_public_plan(
        public_plan,
        manifest,
        exploration_evidence=exploration_evidence,
    )
    accuracy = validate_score_report(accuracy_report)
    proof = validate_engineering_proof(
        engineering_proof,
        accuracy_output,
        plan,
        manifest,
        protocol_document,
        exploration_evidence=exploration_evidence,
        exploration_manifest=exploration_manifest,
        exploration_public_plan=exploration_public_plan,
        exploration_dataset_protocol=exploration_dataset_protocol,
    )
    if (
        accuracy["run_id"] != proof["run_id"]
        or accuracy["dataset_id"] != proof["dataset_id"]
        or accuracy["model_alias"] != proof["model_alias"]
        or accuracy["evaluation_contract"]["accuracy_output_sha256"]
        != proof["accuracy_output_sha256"]
        or accuracy["evaluation_contract"]["public_plan_sha256"]
        != canonical_sha256(plan)
    ):
        raise ContractError(
            "engineering proof must bind the already-frozen anonymous accuracy report"
        )

    clips_by_id = {
        clip["clip_id"]: clip for clip in manifest_document["clips"]
    }
    normal_short_latencies = [
        float(measurement["stop_to_final_ms"])
        for measurement in proof["clip_measurements"]
        if "normal_short" in clips_by_id[measurement["clip_id"]]["conditions"]
    ]
    runtime = proof["runtime"]
    telemetry = proof["runner_proof"]["telemetry"]
    report = {
        "schema_version": "1.0",
        "run_id": proof["run_id"],
        "dataset_id": proof["dataset_id"],
        "model_alias": proof["model_alias"],
        "accuracy_report_sha256": canonical_sha256(accuracy),
        "accuracy_output_sha256": proof["accuracy_output_sha256"],
        "public_plan_sha256": canonical_sha256(plan),
        "engineering_proof_sha256": canonical_sha256(proof),
        "trusted_runner_status": "phase_b_required",
        "hard_gates": {
            "total_resource_bytes": runtime["total_resource_bytes"],
            "peak_rss_bytes": runtime["peak_rss_bytes"],
            "oom_count": runtime["oom_count"],
            "crash_count": runtime["crash_count"],
            "cold_start": _timing_summary(
                [float(value) for value in runtime["cold_start_ms"]]
            ),
            "warm_start": _timing_summary(
                [float(value) for value in runtime["warm_start_ms"]]
            ),
            "stability": copy.deepcopy(runtime["stability"]),
            "normal_short_stop_to_final": _timing_summary(
                normal_short_latencies
            ),
            "decode_failure_count": sum(
                measurement["status"] == "error"
                for measurement in proof["clip_measurements"]
            ),
            "loop_count": telemetry["loop_count"],
            "successful_loop_count": telemetry["successful_loop_count"],
        },
    }
    validated_report = validate_engineering_report(
        report,
        accuracy_report=accuracy,
        public_plan=plan,
    )
    _revalidate_formal_snapshot_bindings(
        manifest,
        protocol_document["profile"],
        exploration_evidence=exploration_evidence,
    )
    return validated_report


def _validate_timing_summary(value: Any, context: str) -> None:
    summary = _require_mapping(value, context)
    _exact_keys(
        summary,
        {"count", "p50_ms", "p95_ms", "max_ms"},
        context=context,
    )
    count = _require_nonnegative_int(summary["count"], f"{context}.count")
    for field in ("p50_ms", "p95_ms", "max_ms"):
        timing = summary[field]
        if count == 0:
            if timing is not None:
                raise ContractError(f"{context} empty timing summary must use null values")
        else:
            _require_nonnegative_number(timing, f"{context}.{field}")
    if count and not (
        float(summary["p50_ms"])
        <= float(summary["p95_ms"])
        <= float(summary["max_ms"])
    ):
        raise ContractError(f"{context} percentiles must be ordered")


def validate_engineering_report(
    data: Any,
    *,
    accuracy_report: Optional[Mapping[str, Any]] = None,
    public_plan: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    report = dict(_require_mapping(data, "engineering report"))
    _exact_keys(
        report,
        {
            "schema_version",
            "run_id",
            "dataset_id",
            "model_alias",
            "accuracy_report_sha256",
            "accuracy_output_sha256",
            "public_plan_sha256",
            "engineering_proof_sha256",
            "trusted_runner_status",
            "hard_gates",
        },
        context="engineering report",
    )
    if report["schema_version"] != "1.0":
        raise ContractError("engineering report.schema_version must be 1.0")
    _require_anonymous_id(report["run_id"], "run", "engineering report.run_id")
    _require_anonymous_id(
        report["dataset_id"],
        "dataset",
        "engineering report.dataset_id",
    )
    alias = _require_string(report["model_alias"], "engineering report.model_alias")
    if len(alias) != 4 or not alias.startswith("M") or not alias[1:].isdigit():
        raise ContractError("engineering report.model_alias is invalid")
    for field in (
        "accuracy_report_sha256",
        "accuracy_output_sha256",
        "public_plan_sha256",
        "engineering_proof_sha256",
    ):
        _require_sha256(report[field], f"engineering report.{field}")
    if report["trusted_runner_status"] != "phase_b_required":
        raise ContractError(
            "Phase A engineering report must remain phase_b_required"
        )

    gates = _require_mapping(report["hard_gates"], "engineering report.hard_gates")
    _exact_keys(
        gates,
        {
            "total_resource_bytes",
            "peak_rss_bytes",
            "oom_count",
            "crash_count",
            "cold_start",
            "warm_start",
            "stability",
            "normal_short_stop_to_final",
            "decode_failure_count",
            "loop_count",
            "successful_loop_count",
        },
        context="engineering report.hard_gates",
    )
    for field in (
        "total_resource_bytes",
        "peak_rss_bytes",
        "oom_count",
        "crash_count",
        "decode_failure_count",
        "loop_count",
        "successful_loop_count",
    ):
        _require_nonnegative_int(gates[field], f"engineering report.hard_gates.{field}")
    if gates["successful_loop_count"] > gates["loop_count"]:
        raise ContractError("engineering report successful loops exceed all loops")
    for field in ("cold_start", "warm_start", "normal_short_stop_to_final"):
        _validate_timing_summary(gates[field], f"engineering report.hard_gates.{field}")
    stability = _require_mapping(
        gates["stability"],
        "engineering report.hard_gates.stability",
    )
    _exact_keys(
        stability,
        {"duration_seconds", "failure_count", "completed", "max_thermal_status"},
        context="engineering report.hard_gates.stability",
    )
    _require_nonnegative_number(
        stability["duration_seconds"],
        "engineering report.hard_gates.stability.duration_seconds",
    )
    _require_nonnegative_int(
        stability["failure_count"],
        "engineering report.hard_gates.stability.failure_count",
    )
    if not isinstance(stability["completed"], bool):
        raise ContractError("engineering report stability.completed must be boolean")
    thermal = _require_nonnegative_int(
        stability["max_thermal_status"],
        "engineering report.hard_gates.stability.max_thermal_status",
    )
    if thermal > 6:
        raise ContractError("engineering report thermal status must be 0..6")

    if accuracy_report is not None:
        accuracy = validate_score_report(accuracy_report)
        if (
            report["run_id"] != accuracy["run_id"]
            or report["dataset_id"] != accuracy["dataset_id"]
            or report["model_alias"] != accuracy["model_alias"]
            or report["accuracy_report_sha256"] != canonical_sha256(accuracy)
            or report["accuracy_output_sha256"]
            != accuracy["evaluation_contract"]["accuracy_output_sha256"]
        ):
            raise ContractError(
                "engineering report does not match its frozen accuracy report"
            )
    if public_plan is not None:
        plan = validate_public_plan(public_plan)
        if (
            report["run_id"] != plan["run_id"]
            or report["dataset_id"] != plan["dataset_id"]
            or report["public_plan_sha256"] != canonical_sha256(plan)
            or report["model_alias"]
            not in {item["model_alias"] for item in plan["assignments"]}
        ):
            raise ContractError("engineering report does not match the public plan")
    return report
