from __future__ import annotations

import hashlib
import math
import random
from dataclasses import asdict, dataclass
from decimal import Decimal, InvalidOperation, ROUND_CEILING, ROUND_FLOOR
from fractions import Fraction
from typing import Any, Mapping, Optional, Sequence

from .contracts import (
    ContractError,
    _exact_keys,
    _require_anonymous_id,
    _require_list,
    _require_mapping,
    _require_sha256,
    _require_string,
    canonical_sha256,
    validate_public_plan,
)
from .metrics import validate_score_report
from .engineering import validate_engineering_report


_FORMAL_CER_DELTA = Decimal("0.005")
_FORMAL_CER_DELTA_FRACTION = Fraction(1, 200)
_SLICE_DEGRADATION_DELTA = Decimal("0.02")


def _decimal_number(value: Any, context: str) -> Decimal:
    if isinstance(value, bool):
        raise ContractError(f"{context} must be a finite decimal number")
    try:
        result = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as error:
        raise ContractError(f"{context} must be a finite decimal number") from error
    if not result.is_finite():
        raise ContractError(f"{context} must be a finite decimal number")
    return result


@dataclass(frozen=True)
class SelectionConfig:
    max_total_resource_bytes: int = 2_000_000_000
    max_normal_short_stop_to_final_ms: float = 5_000.0
    latency_statistic: str = "max"
    min_stability_seconds: float = 600.0
    practical_cer_delta: float = 0.005
    confidence_level: float = 0.95
    bootstrap_samples: int = 10_000
    bootstrap_seed: int = 20_260_729

    def __post_init__(self) -> None:
        if self.max_total_resource_bytes <= 0:
            raise ValueError("max_total_resource_bytes must be positive")
        if self.max_normal_short_stop_to_final_ms <= 0:
            raise ValueError("max_normal_short_stop_to_final_ms must be positive")
        if self.latency_statistic not in {"max", "p95"}:
            raise ValueError("latency_statistic must be max or p95")
        if self.min_stability_seconds <= 0:
            raise ValueError("min_stability_seconds must be positive")
        if self.practical_cer_delta < 0:
            raise ValueError("practical_cer_delta must be non-negative")
        if not 0 < self.confidence_level < 1:
            raise ValueError("confidence_level must be between zero and one")
        if self.bootstrap_samples < 1:
            raise ValueError("bootstrap_samples must be positive")
        if isinstance(self.bootstrap_seed, bool) or not isinstance(self.bootstrap_seed, int):
            raise ValueError("bootstrap_seed must be an integer")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def evaluate_hard_gates(
    report: Mapping[str, Any],
    config: SelectionConfig,
    engineering_report: Optional[Mapping[str, Any]] = None,
) -> list[str]:
    report = validate_score_report(report)
    reasons = []
    if engineering_report is None:
        reasons.append("sanitized anonymous engineering report is missing")
        engineering = None
    else:
        engineering = validate_engineering_report(
            engineering_report,
            accuracy_report=report,
        )
    gates = engineering["hard_gates"] if engineering is not None else None
    if gates is not None and gates["total_resource_bytes"] > config.max_total_resource_bytes:
        reasons.append("resource total exceeds the 2 GB gate")
    if gates is not None and gates["oom_count"] > 0:
        reasons.append("OOM occurred")
    if gates is not None and gates["crash_count"] > 0:
        reasons.append("crash occurred")
    stability = gates["stability"] if gates is not None else None
    if stability is not None and (
        not stability["completed"]
        or stability["duration_seconds"] < config.min_stability_seconds
        or stability["failure_count"] > 0
    ):
        reasons.append("10-minute stability gate failed")
    if report["metrics"]["status_counts"]["error"] > 0:
        reasons.append("one or more decoding attempts failed")
    qualification = report["dataset_summary"]["protocol_qualification"]
    if not qualification["eligible"]:
        reasons.extend(qualification["reasons"])
    generalizability = report["dataset_summary"]["generalizability"]
    if not generalizability["eligible"]:
        reasons.extend(
            reason
            for reason in generalizability["reasons"]
            if reason not in reasons
        )
    if gates is not None and gates["successful_loop_count"] != gates["loop_count"]:
        reasons.append("trusted runner decode loop failures occurred")

    normal_short = (
        gates["normal_short_stop_to_final"] if gates is not None else None
    )
    if normal_short is None or normal_short["count"] == 0:
        reasons.append("normal-short latency samples are missing")
    else:
        field = "max_ms" if config.latency_statistic == "max" else "p95_ms"
        latency = normal_short.get(field)
        if latency is None:
            reasons.append("normal-short latency samples are missing")
        elif latency > config.max_normal_short_stop_to_final_ms:
            reasons.append(
                f"normal-short {config.latency_statistic} latency exceeds the configured gate"
            )
    return reasons


def _quantile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def _decimal_quantile(
    values: list[Decimal],
    fraction: Decimal,
) -> Decimal:
    ordered = sorted(values)
    position = Decimal(len(ordered) - 1) * fraction
    lower = int(position.to_integral_value(rounding=ROUND_FLOOR))
    upper = int(position.to_integral_value(rounding=ROUND_CEILING))
    if lower == upper:
        return ordered[lower]
    weight = position - Decimal(lower)
    return ordered[lower] * (Decimal(1) - weight) + ordered[upper] * weight


def _clip_edit_stats(
    report: Mapping[str, Any],
) -> dict[str, tuple[int, int, str, str]]:
    return {
        item["clip_id"]: (
            int(item["substitutions"]) + int(item["deletions"]) + int(item["insertions"]),
            int(item["reference_characters"]),
            item["speaker_cluster_id"],
            item["session_cluster_id"],
        )
        for item in report["sentence_errors"]
    }


def _report_cer_fraction(report: Mapping[str, Any]) -> Fraction:
    sentence_errors = _require_list(
        report.get("sentence_errors"),
        "model report.sentence_errors",
        non_empty=True,
    )
    edits = 0
    reference_characters = 0
    for index, raw_item in enumerate(sentence_errors):
        item = _require_mapping(
            raw_item,
            f"model report.sentence_errors[{index}]",
        )
        for field in (
            "substitutions",
            "deletions",
            "insertions",
            "reference_characters",
        ):
            value = item.get(field)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ContractError(
                    "model report integer edit and reference totals are invalid"
                )
        edits += (
            item["substitutions"]
            + item["deletions"]
            + item["insertions"]
        )
        reference_characters += item["reference_characters"]
    return Fraction(edits, reference_characters or 1)


def _paired_bootstrap_interval(
    best: Mapping[str, Any],
    contender: Mapping[str, Any],
    config: SelectionConfig,
) -> tuple[Decimal, Decimal]:
    best_stats = _clip_edit_stats(best)
    contender_stats = _clip_edit_stats(contender)
    if set(best_stats) != set(contender_stats) or not best_stats:
        raise ContractError("paired model reports must contain the same non-empty clip set")
    if any(best_stats[clip_id][1:] != contender_stats[clip_id][1:] for clip_id in best_stats):
        raise ContractError(
            "paired model reports must share reference lengths and speaker/session clusters"
        )
    hierarchy: dict[str, dict[str, list[str]]] = {}
    for clip_id, (_, _, speaker_id, session_id) in best_stats.items():
        hierarchy.setdefault(speaker_id, {}).setdefault(session_id, []).append(clip_id)
    speakers = sorted(hierarchy)
    seed_material = (
        f"{config.bootstrap_seed}:{best['model_alias']}:{contender['model_alias']}"
    ).encode("utf-8")
    pair_seed = int.from_bytes(hashlib.sha256(seed_material).digest()[:8], "big")
    randomizer = random.Random(pair_seed)
    deltas: list[Decimal] = []
    for _ in range(config.bootstrap_samples):
        sampled = []
        sampled_speakers = [
            speakers[randomizer.randrange(len(speakers))] for _ in speakers
        ]
        for speaker_id in sampled_speakers:
            sessions = sorted(hierarchy[speaker_id])
            sampled_sessions = [
                sessions[randomizer.randrange(len(sessions))] for _ in sessions
            ]
            for session_id in sampled_sessions:
                clips = sorted(hierarchy[speaker_id][session_id])
                sampled.extend(
                    clips[randomizer.randrange(len(clips))] for _ in clips
                )
        reference_characters = sum(best_stats[item][1] for item in sampled)
        denominator = reference_characters if reference_characters else 1
        edit_delta = sum(
            contender_stats[item][0] - best_stats[item][0]
            for item in sampled
        )
        delta = Decimal(edit_delta) / Decimal(denominator)
        deltas.append(delta)
    confidence = _decimal_number(
        config.confidence_level,
        "selection confidence_level",
    )
    tail = (Decimal(1) - confidence) / Decimal(2)
    return (
        _decimal_quantile(deltas, tail),
        _decimal_quantile(deltas, Decimal(1) - tail),
    )


def _secondary_key(
    report: Mapping[str, Any],
    engineering_report: Mapping[str, Any],
    config: SelectionConfig,
) -> tuple[Any, ...]:
    gates = engineering_report["hard_gates"]
    stability = gates["stability"]
    normal_short = gates["normal_short_stop_to_final"]
    latency = (
        normal_short["max_ms"]
        if config.latency_statistic == "max"
        else normal_short["p95_ms"]
    )
    return (
        stability["failure_count"],
        0 if stability["completed"] else 1,
        -float(stability["duration_seconds"]),
        stability["max_thermal_status"],
        float(latency),
        gates["peak_rss_bytes"],
        gates["total_resource_bytes"],
        report["model_alias"],
    )


def _slice_report(
    report: Mapping[str, Any],
    condition: str,
) -> dict[str, Any]:
    return {
        "model_alias": report["model_alias"],
        "sentence_errors": [
            item
            for item in report["sentence_errors"]
            if condition in item["conditions"]
        ],
    }


def _production_rule_assessment(
    leader: Mapping[str, Any],
    baseline: Mapping[str, Any],
    runner_up: Mapping[str, Any],
    config: SelectionConfig,
) -> dict[str, Any]:
    profile = leader["dataset_summary"]["protocol_profile"]
    if profile != "production_confirmation":
        return {
            "eligible_profile": False,
            "overall_improvement": None,
            "overall_confidence_interval": None,
            "ci_half_width": None,
            "runner_up_improvement": None,
            "runner_up_confidence_interval": None,
            "runner_up_ci_half_width": None,
            "slice_degradation_evidence": [],
            "recommended_action": (
                "exploration_can_eliminate_only"
                if profile == "exploration"
                else "development_fixture_only"
            ),
        }
    delta_fraction = _report_cer_fraction(baseline) - _report_cer_fraction(
        leader
    )
    lower_raw, upper_raw = _paired_bootstrap_interval(
        leader,
        baseline,
        config,
    )
    lower = _decimal_number(lower_raw, "baseline CI lower bound")
    upper = _decimal_number(upper_raw, "baseline CI upper bound")
    half_width = (upper - lower) / Decimal(2)
    runner_up_delta_fraction = _report_cer_fraction(
        runner_up
    ) - _report_cer_fraction(leader)
    runner_up_lower_raw, runner_up_upper_raw = _paired_bootstrap_interval(
        leader,
        runner_up,
        config,
    )
    runner_up_lower = _decimal_number(
        runner_up_lower_raw,
        "runner-up CI lower bound",
    )
    runner_up_upper = _decimal_number(
        runner_up_upper_raw,
        "runner-up CI upper bound",
    )
    runner_up_half_width = (
        runner_up_upper - runner_up_lower
    ) / Decimal(2)
    degraded_slices = []
    for condition in sorted(leader["groups"]):
        candidate_subset = _slice_report(leader, condition)
        baseline_subset = _slice_report(baseline, condition)
        if not candidate_subset["sentence_errors"]:
            continue
        slice_lower_raw, slice_upper_raw = _paired_bootstrap_interval(
            baseline_subset,
            candidate_subset,
            config,
        )
        slice_lower = _decimal_number(
            slice_lower_raw,
            f"{condition} degradation CI lower bound",
        )
        slice_upper = _decimal_number(
            slice_upper_raw,
            f"{condition} degradation CI upper bound",
        )
        if slice_lower >= _SLICE_DEGRADATION_DELTA:
            degraded_slices.append(
                {
                    "condition": condition,
                    "candidate_minus_baseline_confidence_interval": [
                        float(slice_lower),
                        float(slice_upper),
                    ],
                }
            )
    clip_count = leader["dataset_summary"]["clip_count"]
    statistically_ahead = lower > Decimal(0)
    practically_ahead = delta_fraction >= _FORMAL_CER_DELTA_FRACTION
    runner_up_clear = (
        runner_up_lower > Decimal(0)
        and runner_up_delta_fraction >= _FORMAL_CER_DELTA_FRACTION
        and runner_up_half_width <= _FORMAL_CER_DELTA
    )
    extend_or_keep = (
        "expand_by_120_with_2_new_speakers"
        if clip_count < 720
        else "keep_current_baseline"
    )
    baseline_uncertain = practically_ahead and (
        not statistically_ahead or half_width > _FORMAL_CER_DELTA
    )
    if not practically_ahead or degraded_slices:
        action = "keep_current_baseline"
    elif baseline_uncertain:
        action = extend_or_keep
    elif not runner_up_clear:
        action = extend_or_keep
    else:
        action = "phase_b_runner_attestation_required"
    return {
        "eligible_profile": True,
        "overall_improvement": float(delta_fraction),
        "overall_confidence_interval": [float(lower), float(upper)],
        "ci_half_width": float(half_width),
        "runner_up_improvement": float(runner_up_delta_fraction),
        "runner_up_confidence_interval": [
            float(runner_up_lower),
            float(runner_up_upper),
        ],
        "runner_up_ci_half_width": float(runner_up_half_width),
        "slice_degradation_evidence": degraded_slices,
        "recommended_action": action,
    }


_FAILURE_REASON_CODES = {
    "runner_failed",
    "oom",
    "crash",
    "decode_error",
    "resource_gate_failed",
    "stability_gate_failed",
}


def _validate_failure_record(
    data: Any,
    *,
    plan: Mapping[str, Any],
) -> dict[str, Any]:
    value = dict(_require_mapping(data, "selection failure record"))
    _exact_keys(
        value,
        {
            "schema_version",
            "run_id",
            "dataset_id",
            "model_alias",
            "public_plan_sha256",
            "registry_sha256",
            "reason_code",
            "attestation_status",
        },
        context="selection failure record",
    )
    if value["schema_version"] != "1.0":
        raise ContractError("selection failure schema_version must be 1.0")
    _require_anonymous_id(value["run_id"], "run", "selection failure run_id")
    _require_anonymous_id(value["dataset_id"], "dataset", "selection failure dataset_id")
    alias = _require_string(value["model_alias"], "selection failure model_alias")
    if alias not in {item["model_alias"] for item in plan["assignments"]}:
        raise ContractError("selection failure alias is not in the public plan")
    _require_sha256(value["public_plan_sha256"], "selection failure public_plan_sha256")
    _require_sha256(value["registry_sha256"], "selection failure registry_sha256")
    if (
        value["run_id"] != plan["run_id"]
        or value["dataset_id"] != plan["dataset_id"]
        or value["public_plan_sha256"] != canonical_sha256(plan)
        or value["registry_sha256"] != plan["contract_fingerprints"]["registry_sha256"]
    ):
        raise ContractError("selection failure record does not match the same frozen run")
    if value["reason_code"] not in _FAILURE_REASON_CODES:
        raise ContractError("selection failure reason_code is invalid")
    if plan["protocol_profile"] in {"exploration", "production_confirmation"}:
        if value["attestation_status"] != "phase_b_attestation_required":
            raise ContractError(
                "formal selection failure requires Phase B attestation"
            )
    elif value["attestation_status"] != "development_untrusted":
        raise ContractError(
            "development failure record must be marked development_untrusted"
        )
    return value


def _failure_record_closes_candidate(
    failure: Mapping[str, Any],
    *,
    plan: Mapping[str, Any],
) -> bool:
    return (
        plan["protocol_profile"] == "development_fixture"
        and failure["attestation_status"] == "development_untrusted"
    )


def _cohort_value(report: Mapping[str, Any]) -> dict[str, Any]:
    evaluation = report["evaluation_contract"]
    return {
        "run_id": report["run_id"],
        "dataset_id": report["dataset_id"],
        "normalization": report["normalization"],
        "manifest_sha256": evaluation["manifest_sha256"],
        "reference_set_sha256": evaluation["reference_set_sha256"],
        "public_plan_sha256": evaluation["public_plan_sha256"],
        "dataset_protocol_sha256": evaluation["dataset_protocol_sha256"],
        "selection_config_sha256": evaluation["selection_config_sha256"],
        "registry_sha256": evaluation["registry_sha256"],
        "clip_set_sha256": evaluation["clip_set_sha256"],
        "reference_lengths_sha256": evaluation["reference_lengths_sha256"],
        "pcm_set_sha256": evaluation["pcm_set_sha256"],
        "decoder_contract_id": evaluation["decoder_contract_id"],
        "pcm_contract_id": evaluation["pcm_contract_id"],
        "input_transform_id": evaluation["input_transform_id"],
        "dataset_summary_sha256": canonical_sha256(report["dataset_summary"]),
    }


def _require_comparable_reports(
    reports: Sequence[Mapping[str, Any]],
    *,
    plan: Mapping[str, Any],
    config: SelectionConfig,
) -> list[dict[str, Any]]:
    validated = [validate_score_report(report) for report in reports]
    if not validated:
        raise ContractError("selection requires at least one score report")
    expected_cohort = _cohort_value(validated[0])
    expected_config_hash = canonical_sha256(config.to_dict())
    plan_hash = canonical_sha256(plan)
    assignments = {
        item["model_alias"]: item["clip_order"] for item in plan["assignments"]
    }
    for report in validated:
        if _cohort_value(report) != expected_cohort:
            raise ContractError("all model reports must share the same frozen evaluation cohort")
        evaluation = report["evaluation_contract"]
        if (
            evaluation["public_plan_sha256"] != plan_hash
            or evaluation["selection_config_sha256"] != expected_config_hash
            or evaluation["selection_config_sha256"]
            != plan["contract_fingerprints"]["selection_config_sha256"]
        ):
            raise ContractError("model reports must match the same public plan and selection config")
        alias = report["model_alias"]
        if alias not in assignments:
            raise ContractError("model report alias is absent from the public plan")
        actual_order = [item["clip_id"] for item in report["sentence_errors"]]
        if actual_order != assignments[alias]:
            raise ContractError("model report clip order must match its public plan assignment")
        if evaluation["prediction_order_sha256"] != canonical_sha256(actual_order):
            raise ContractError("model report prediction order fingerprint does not match")
    return validated


def select_winner(
    reports: Sequence[Mapping[str, Any]],
    config: SelectionConfig,
    *,
    public_plan: Mapping[str, Any],
    failure_records: Sequence[Mapping[str, Any]] = (),
    engineering_reports: Sequence[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    plan = validate_public_plan(public_plan)
    validated = _require_comparable_reports(reports, plan=plan, config=config)
    ordered = sorted(validated, key=lambda report: report["model_alias"])
    aliases = [report["model_alias"] for report in ordered]
    if len(aliases) != len(set(aliases)):
        raise ContractError("model reports contain duplicate aliases")
    failures = [
        _validate_failure_record(item, plan=plan) for item in failure_records
    ]
    failure_aliases = [item["model_alias"] for item in failures]
    if len(failure_aliases) != len(set(failure_aliases)):
        raise ContractError("selection failure records contain duplicate aliases")
    if set(aliases) & set(failure_aliases):
        raise ContractError("an alias cannot have both a score report and a failure record")
    closing_failure_aliases = {
        failure["model_alias"]
        for failure in failures
        if _failure_record_closes_candidate(failure, plan=plan)
    }
    engineering_by_alias: dict[str, dict[str, Any]] = {}
    for raw_engineering in engineering_reports:
        raw_alias = (
            raw_engineering.get("model_alias")
            if isinstance(raw_engineering, Mapping)
            else None
        )
        accuracy = next(
            (report for report in ordered if report["model_alias"] == raw_alias),
            None,
        )
        if accuracy is None:
            raise ContractError(
                "engineering report alias must have a corresponding accuracy report"
            )
        engineering = validate_engineering_report(
            raw_engineering,
            accuracy_report=accuracy,
            public_plan=plan,
        )
        alias = engineering["model_alias"]
        if alias in engineering_by_alias:
            raise ContractError("engineering reports contain duplicate aliases")
        engineering_by_alias[alias] = engineering
    expected_aliases = [item["model_alias"] for item in plan["assignments"]]
    missing_aliases = sorted(
        set(expected_aliases) - set(aliases) - closing_failure_aliases
    )
    unresolved_failure_aliases = sorted(
        set(failure_aliases) - closing_failure_aliases
    )
    missing_engineering_aliases = sorted(set(aliases) - set(engineering_by_alias))
    eliminated = {}
    for failure in failures:
        eliminated[failure["model_alias"]] = [
            f"explicit runner failure: {failure['reason_code']}"
        ]
    eligible = []
    for report in ordered:
        reasons = evaluate_hard_gates(
            report,
            config,
            engineering_by_alias.get(report["model_alias"]),
        )
        if reasons:
            eliminated[report["model_alias"]] = reasons
        else:
            eligible.append(report)

    cohort = _cohort_value(ordered[0])
    base: dict[str, Any] = {
        "schema_version": "1.0",
        "run_id": plan["run_id"],
        "dataset_id": plan["dataset_id"],
        "cohort": cohort,
        "selection_config": config.to_dict(),
        "eliminated": eliminated,
        "eligible_aliases": [report["model_alias"] for report in eligible],
        "missing_aliases": missing_aliases,
        "unresolved_failure_aliases": unresolved_failure_aliases,
        "missing_engineering_aliases": missing_engineering_aliases,
        "report_sha256_by_alias": {
            report["model_alias"]: canonical_sha256(report) for report in ordered
        },
        "engineering_report_sha256_by_alias": {
            alias: canonical_sha256(report)
            for alias, report in sorted(engineering_by_alias.items())
        },
        "accuracy_bundle_sha256": canonical_sha256(
            {
                report["model_alias"]: canonical_sha256(report)
                for report in ordered
            }
        ),
        "protocol_profile": ordered[0]["dataset_summary"]["protocol_profile"],
        "trusted_runner_status": "phase_b_required",
        "production_blockers": [
            "trusted_android_runner_attestation",
            "component_licenses_phase_b_verification",
        ],
        "production_integration_eligible": False,
        "production_rule_assessment": None,
    }
    if missing_aliases:
        return {
            **base,
            "status": "no_result_incomplete_candidate_set",
            "winner_alias": None,
            "screening_leader_alias": None,
            "decision_basis": None,
            "comparisons": [],
        }
    if missing_engineering_aliases:
        return {
            **base,
            "status": "no_result_incomplete_engineering_set",
            "winner_alias": None,
            "screening_leader_alias": None,
            "decision_basis": None,
            "comparisons": [],
        }
    if len(ordered) < 2:
        return {
            **base,
            "status": "no_result_insufficient_scored_models",
            "winner_alias": None,
            "screening_leader_alias": None,
            "decision_basis": None,
            "comparisons": [],
        }
    if not eligible:
        return {
            **base,
            "status": "no_eligible_model",
            "winner_alias": None,
            "screening_leader_alias": None,
            "decision_basis": None,
            "comparisons": [],
        }

    best = min(
        eligible,
        key=lambda report: (
            _report_cer_fraction(report),
            report["model_alias"],
        ),
    )
    tied = [best]
    comparisons = []
    practical_threshold = Fraction(
        _decimal_number(
            config.practical_cer_delta,
            "selection practical_cer_delta",
        )
    )
    for contender in eligible:
        if contender["model_alias"] == best["model_alias"]:
            continue
        delta_fraction = _report_cer_fraction(
            contender
        ) - _report_cer_fraction(best)
        lower_raw, upper_raw = _paired_bootstrap_interval(
            best,
            contender,
            config,
        )
        lower = _decimal_number(lower_raw, "paired CI lower bound")
        upper = _decimal_number(upper_raw, "paired CI upper bound")
        statistically_meaningful = (
            lower > Decimal(0) or upper < Decimal(0)
        )
        practically_meaningful = (
            abs(delta_fraction) >= practical_threshold
        )
        is_tied = not statistically_meaningful and not practically_meaningful
        comparisons.append(
            {
                "best_alias": best["model_alias"],
                "contender_alias": contender["model_alias"],
                "cer_delta": float(delta_fraction),
                "paired_confidence_interval": [
                    float(lower),
                    float(upper),
                ],
                "statistically_meaningful": statistically_meaningful,
                "practically_meaningful": practically_meaningful,
                "treated_as_tie": is_tied,
            }
        )
        if is_tied:
            tied.append(contender)

    if len(tied) == 1:
        winner = best
        basis = "lowest_cer"
    else:
        winner = min(
            tied,
            key=lambda report: _secondary_key(
                report,
                engineering_by_alias[report["model_alias"]],
                config,
            ),
        )
        basis = "secondary_tiebreak"
    profile = winner["dataset_summary"]["protocol_profile"]
    assessment = (
        {
            "eligible_profile": True,
            "overall_improvement": None,
            "overall_confidence_interval": None,
            "ci_half_width": None,
            "runner_up_improvement": None,
            "runner_up_confidence_interval": None,
            "runner_up_ci_half_width": None,
            "slice_degradation_evidence": [],
            "recommended_action": "controlled_phase_b_baseline_merge_required",
        }
        if winner["dataset_summary"]["protocol_profile"]
        == "production_confirmation"
        else _production_rule_assessment(winner, winner, winner, config)
    )
    status = {
        "development_fixture": "development_screening_only",
        "exploration": "exploration_elimination_only",
        "production_confirmation": "screening_only_phase_b_required",
    }[profile]
    return {
        **base,
        "status": status,
        "winner_alias": None,
        "screening_leader_alias": winner["model_alias"],
        "decision_basis": basis,
        "comparisons": comparisons,
        "production_rule_assessment": assessment,
    }
