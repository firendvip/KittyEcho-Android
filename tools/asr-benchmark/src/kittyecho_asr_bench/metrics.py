from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Optional

from .contracts import (
    DECODER_CONTRACT_ID,
    INPUT_TRANSFORM_ID,
    REQUIRED_SELECTION_CONDITIONS,
    SOURCE_KINDS,
    ContractError,
    VerifiedExplorationEvidence,
    _exact_keys,
    _manifest_document_for_profile,
    _revalidate_formal_snapshot_bindings,
    _require_anonymous_id,
    _require_list,
    _require_mapping,
    _require_nonnegative_int,
    _require_nonnegative_number,
    _require_sha256,
    _require_string,
    canonical_sha256,
    validate_dataset_protocol,
    validate_model_output,
    validate_public_plan,
    validate_reference_set,
)
from .normalization import NormalizationConfig, normalize_text


@dataclass(frozen=True)
class EditStats:
    substitutions: int
    deletions: int
    insertions: int
    reference_characters: int
    head_deleted_chars: int
    tail_deleted_chars: int

    @property
    def edits(self) -> int:
        return self.substitutions + self.deletions + self.insertions

    @property
    def cer(self) -> float:
        if self.reference_characters == 0:
            return 0.0 if self.edits == 0 else float(self.edits)
        return self.edits / self.reference_characters


def _alignment(reference: str, hypothesis: str) -> list[tuple[str, Optional[str], Optional[str]]]:
    rows = len(reference) + 1
    columns = len(hypothesis) + 1
    distance = [[0] * columns for _ in range(rows)]
    for row in range(rows):
        distance[row][0] = row
    for column in range(columns):
        distance[0][column] = column
    for row in range(1, rows):
        for column in range(1, columns):
            substitution_cost = 0 if reference[row - 1] == hypothesis[column - 1] else 1
            distance[row][column] = min(
                distance[row - 1][column] + 1,
                distance[row][column - 1] + 1,
                distance[row - 1][column - 1] + substitution_cost,
            )

    operations: list[tuple[str, Optional[str], Optional[str]]] = []
    row = len(reference)
    column = len(hypothesis)
    while row or column:
        if (
            row
            and column
            and reference[row - 1] == hypothesis[column - 1]
            and distance[row][column] == distance[row - 1][column - 1]
        ):
            operations.append(("match", reference[row - 1], hypothesis[column - 1]))
            row -= 1
            column -= 1
        elif (
            row
            and column
            and distance[row][column] == distance[row - 1][column - 1] + 1
        ):
            operations.append(("substitute", reference[row - 1], hypothesis[column - 1]))
            row -= 1
            column -= 1
        elif row and distance[row][column] == distance[row - 1][column] + 1:
            operations.append(("delete", reference[row - 1], None))
            row -= 1
        else:
            operations.append(("insert", None, hypothesis[column - 1]))
            column -= 1
    operations.reverse()
    return operations


def _boundary_deletions(
    operations: list[tuple[str, Optional[str], Optional[str]]],
    *,
    reverse: bool,
) -> int:
    count = 0
    sequence = reversed(operations) if reverse else iter(operations)
    for operation, reference_character, _ in sequence:
        if operation == "insert":
            continue
        if operation == "delete":
            count += 1
            continue
        if reference_character is not None:
            break
    return count


def edit_stats(reference: str, hypothesis: str) -> EditStats:
    operations = _alignment(reference, hypothesis)
    return EditStats(
        substitutions=sum(operation == "substitute" for operation, _, _ in operations),
        deletions=sum(operation == "delete" for operation, _, _ in operations),
        insertions=sum(operation == "insert" for operation, _, _ in operations),
        reference_characters=len(reference),
        head_deleted_chars=_boundary_deletions(operations, reverse=False),
        tail_deleted_chars=_boundary_deletions(operations, reverse=True),
    )


def _sequence_edit_count(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for row, reference_item in enumerate(reference, start=1):
        current = [row]
        for column, hypothesis_item in enumerate(hypothesis, start=1):
            current.append(
                min(
                    previous[column] + 1,
                    current[column - 1] + 1,
                    previous[column - 1] + (reference_item != hypothesis_item),
                )
            )
        previous = current
    return previous[-1]


def _chinese_characters(value: str) -> str:
    return "".join(
        character
        for character in value
        if (
            "\u3400" <= character <= "\u4dbf"
            or "\u4e00" <= character <= "\u9fff"
            or "\uf900" <= character <= "\ufaff"
        )
    )


def _latin_characters(value: str) -> str:
    return "".join(character for character in value if character.isascii() and character.isalpha())


def _latin_words(value: str) -> list[str]:
    return re.findall(r"[a-z]+(?:'[a-z]+)?", value.lower())


def _egc_count(value: str) -> int:
    """Deterministic EGC counter for the Chinese/Latin benchmark domain.

    It keeps combining marks, variation selectors, and ZWJ-linked code points
    with their base. This avoids a third-party Unicode dependency while the
    frozen profile remains limited to ordinary Chinese/Latin prompts.
    """
    count = 0
    join_next = False
    for character in value:
        codepoint = ord(character)
        if character == "\u200d":
            join_next = True
            continue
        if (
            unicodedata.combining(character)
            or 0xFE00 <= codepoint <= 0xFE0F
            or 0xE0100 <= codepoint <= 0xE01EF
        ):
            continue
        if join_next:
            join_next = False
            continue
        count += 1
    return count


def _validate_formal_condition_definitions(
    clips: list[Mapping[str, Any]],
    references_by_clip: Mapping[str, Mapping[str, Any]],
    normalization: NormalizationConfig,
    protocol: Mapping[str, Any],
) -> None:
    if protocol["profile"] == "development_fixture":
        return
    definitions = protocol["preregistration"]["condition_definitions"]
    for clip in clips:
        conditions = set(clip["conditions"])
        reference_text = references_by_clip[clip["clip_id"]]["text"]
        normalized_reference = normalize_text(
            reference_text,
            normalization,
        )
        egc_count = _egc_count(normalized_reference)
        duration_seconds = float(clip["speech_duration_ms"]) / 1_000
        rate = egc_count / duration_seconds
        if "short" in conditions and not (
            definitions["short_egc"][0] <= egc_count <= definitions["short_egc"][1]
        ):
            raise ContractError("short slice reference must contain 1-6 EGC")
        if "normal_short" in conditions and not (
            definitions["normal_short_egc"][0]
            <= egc_count
            <= definitions["normal_short_egc"][1]
        ):
            raise ContractError("normal_short slice reference must contain 7-30 EGC")
        if "long" in conditions and egc_count < definitions["long_egc_minimum"]:
            raise ContractError("long slice reference must contain at least 31 EGC")
        if (
            "immediate_start" in conditions
            and clip["speech_onset_ms"] > definitions["immediate_start_maximum_ms"]
        ):
            raise ContractError("immediate_start speech onset must be no later than 300 ms")
        if "fast" in conditions and rate < definitions["fast_minimum_egc_per_second"]:
            raise ContractError("fast slice must be at least 4.5 EGC/s")
        if "slow" in conditions and rate > definitions["slow_maximum_egc_per_second"]:
            raise ContractError("slow slice must be at most 2.5 EGC/s")
        if "code_switch" in conditions and (
            _egc_count(_chinese_characters(normalized_reference))
            < definitions["code_switch_minimum_chinese_egc"]
            or len(_latin_words(unicodedata.normalize("NFKC", reference_text)))
            < definitions["code_switch_minimum_english_words"]
        ):
            raise ContractError(
                "code_switch slice must contain at least four Chinese EGC and two English words"
            )


def _occurrence_spans(value: str, needle: str) -> list[tuple[int, int]]:
    if not needle:
        return []
    spans = []
    start = 0
    while True:
        index = value.find(needle, start)
        if index < 0:
            return spans
        spans.append((index, index + len(needle)))
        start = index + 1


def _alignment_span_is_exact(
    operations: list[tuple[str, Optional[str], Optional[str]]],
    span: tuple[int, int],
) -> bool:
    reference_index = 0
    matched_positions = 0
    for operation, reference_character, _ in operations:
        if reference_character is None:
            if span[0] < reference_index < span[1]:
                return False
            continue
        if span[0] <= reference_index < span[1]:
            if operation != "match":
                return False
            matched_positions += 1
        reference_index += 1
    return matched_positions == span[1] - span[0]


def _script_counts(reference: str, hypothesis: str) -> dict[str, int]:
    chinese_reference = _chinese_characters(reference)
    chinese_hypothesis = _chinese_characters(hypothesis)
    latin_reference = _latin_characters(reference)
    latin_hypothesis = _latin_characters(hypothesis)
    latin_reference_words = _latin_words(reference)
    latin_hypothesis_words = _latin_words(hypothesis)
    return {
        "chinese_edits": edit_stats(chinese_reference, chinese_hypothesis).edits,
        "chinese_reference_characters": len(chinese_reference),
        "latin_edits": edit_stats(latin_reference, latin_hypothesis).edits,
        "latin_reference_characters": len(latin_reference),
        "latin_word_edits": _sequence_edit_count(
            latin_reference_words,
            latin_hypothesis_words,
        ),
        "latin_reference_words": len(latin_reference_words),
    }


def _metrics_from_errors(sentence_errors: list[Mapping[str, Any]]) -> dict[str, Any]:
    substitutions = sum(int(item["substitutions"]) for item in sentence_errors)
    deletions = sum(int(item["deletions"]) for item in sentence_errors)
    insertions = sum(int(item["insertions"]) for item in sentence_errors)
    reference_characters = sum(int(item["reference_characters"]) for item in sentence_errors)
    edits = substitutions + deletions + insertions
    sentence_count = len(sentence_errors)
    number_total = sum(int(item["number_total"]) for item in sentence_errors)
    number_hits = sum(int(item["number_hits"]) for item in sentence_errors)
    proper_name_total = sum(int(item["proper_name_total"]) for item in sentence_errors)
    proper_name_hits = sum(int(item["proper_name_hits"]) for item in sentence_errors)
    chinese_edits = sum(int(item["chinese_edits"]) for item in sentence_errors)
    chinese_reference = sum(
        int(item["chinese_reference_characters"]) for item in sentence_errors
    )
    latin_edits = sum(int(item["latin_edits"]) for item in sentence_errors)
    latin_reference = sum(
        int(item["latin_reference_characters"]) for item in sentence_errors
    )
    latin_word_edits = sum(int(item["latin_word_edits"]) for item in sentence_errors)
    latin_reference_words = sum(int(item["latin_reference_words"]) for item in sentence_errors)
    return {
        "cer": edits / (reference_characters or 1),
        "substitutions": substitutions,
        "deletions": deletions,
        "insertions": insertions,
        "reference_characters": reference_characters,
        "sentence_count": sentence_count,
        "sentence_exact_rate": (
            sum(
                int(item["substitutions"])
                + int(item["deletions"])
                + int(item["insertions"])
                == 0
                for item in sentence_errors
            )
            / sentence_count
            if sentence_count
            else 0.0
        ),
        "head_deleted_chars": sum(int(item["head_deleted_chars"]) for item in sentence_errors),
        "tail_deleted_chars": sum(int(item["tail_deleted_chars"]) for item in sentence_errors),
        "number_recall": number_hits / number_total if number_total else None,
        "proper_name_recall": (
            proper_name_hits / proper_name_total if proper_name_total else None
        ),
        "chinese_cer": chinese_edits / (chinese_reference or 1),
        "latin_cer": latin_edits / (latin_reference or 1),
        "latin_wer": latin_word_edits / (latin_reference_words or 1),
        "status_counts": {
            "ok": sum(item["status"] == "ok" for item in sentence_errors),
            "error": sum(item["status"] == "error" for item in sentence_errors),
        },
    }


def _score_subset(
    clips: list[Mapping[str, Any]],
    references_by_clip: Mapping[str, Mapping[str, Any]],
    predictions_by_clip: Mapping[str, Mapping[str, Any]],
    config: NormalizationConfig,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    sentence_errors: list[dict[str, Any]] = []
    for clip in clips:
        clip_id = clip["clip_id"]
        reference = references_by_clip[clip_id]
        prediction = predictions_by_clip[clip_id]
        normalized_reference = normalize_text(reference["text"], config)
        normalized_hypothesis = normalize_text(prediction["hypothesis"], config)
        operations = _alignment(normalized_reference, normalized_hypothesis)
        stats = edit_stats(normalized_reference, normalized_hypothesis)
        annotation_counts = {
            "number_total": 0,
            "number_hits": 0,
            "proper_name_total": 0,
            "proper_name_hits": 0,
        }
        missing_annotations = []
        declared_annotation_spans: set[tuple[int, int]] = set()
        for annotation in reference["annotations"]:
            kind = annotation["kind"]
            normalized_annotation = normalize_text(annotation["text"], config)
            spans = _occurrence_spans(normalized_reference, normalized_annotation)
            occurrence_index = annotation["occurrence_index"]
            if not normalized_annotation or occurrence_index >= len(spans):
                raise ContractError(
                    f"annotation {annotation['annotation_id']} has no declared occurrence in reference"
                )
            annotation_span = spans[occurrence_index]
            if annotation_span in declared_annotation_spans:
                raise ContractError("duplicate normalized annotation span")
            declared_annotation_spans.add(annotation_span)
            annotation_counts[f"{kind}_total"] += 1
            if _alignment_span_is_exact(operations, annotation_span):
                annotation_counts[f"{kind}_hits"] += 1
            else:
                missing_annotations.append(annotation["annotation_id"])
        sentence_errors.append(
            {
                "clip_id": clip_id,
                "cer": stats.cer,
                "substitutions": stats.substitutions,
                "deletions": stats.deletions,
                "insertions": stats.insertions,
                "reference_characters": stats.reference_characters,
                "head_deleted_chars": stats.head_deleted_chars,
                "tail_deleted_chars": stats.tail_deleted_chars,
                **_script_counts(normalized_reference, normalized_hypothesis),
                **annotation_counts,
                "missing_annotation_ids": missing_annotations,
                "pcm_sha256": prediction["pcm_sha256"],
                "conditions": list(clip["conditions"]),
                "source_kind": clip["source"]["kind"],
                "speaker_cluster_id": clip["speaker_cluster_id"],
                "session_cluster_id": clip["session_cluster_id"],
                "status": prediction["status"],
            }
        )
    return _metrics_from_errors(sentence_errors), sentence_errors


def _dataset_summary(
    sentence_errors: list[Mapping[str, Any]],
    protocol: Mapping[str, Any],
) -> dict[str, Any]:
    condition_counts = {
        condition: sum(condition in item["conditions"] for item in sentence_errors)
        for condition in REQUIRED_SELECTION_CONDITIONS
    }
    source_counts = {
        source: sum(item["source_kind"] == source for item in sentence_errors)
        for source in SOURCE_KINDS
    }
    speakers = {item["speaker_cluster_id"] for item in sentence_errors}
    sessions = {item["session_cluster_id"] for item in sentence_errors}
    joint_clusters = {
        (item["speaker_cluster_id"], item["session_cluster_id"])
        for item in sentence_errors
    }
    condition_speaker_counts = {
        condition: len(
            {
                item["speaker_cluster_id"]
                for item in sentence_errors
                if condition in item["conditions"]
            }
        )
        for condition in REQUIRED_SELECTION_CONDITIONS
    }
    condition_session_counts = {
        condition: len(
            {
                item["session_cluster_id"]
                for item in sentence_errors
                if condition in item["conditions"]
            }
        )
        for condition in REQUIRED_SELECTION_CONDITIONS
    }
    qualification_reasons = []
    for condition in REQUIRED_SELECTION_CONDITIONS:
        minimum = protocol["minimum_unique_clips_per_condition"][condition]
        if condition_counts[condition] < minimum:
            qualification_reasons.append(
                f"{condition} minimum unique clips shortfall: "
                f"{condition_counts[condition]} < {minimum}"
            )
        cluster_minimums = protocol["slice_cluster_minimums"][condition]
        if condition_speaker_counts[condition] < cluster_minimums["minimum_speakers"]:
            qualification_reasons.append(
                f"{condition} speaker coverage shortfall: "
                f"{condition_speaker_counts[condition]} < "
                f"{cluster_minimums['minimum_speakers']}"
            )
        if condition_session_counts[condition] < cluster_minimums["minimum_sessions"]:
            qualification_reasons.append(
                f"{condition} session coverage shortfall: "
                f"{condition_session_counts[condition]} < "
                f"{cluster_minimums['minimum_sessions']}"
            )
    non_target = sum(
        count
        for source, count in source_counts.items()
        if source != protocol["required_source_kind"]
    )
    if non_target:
        qualification_reasons.append(
            "dataset contains audio that is not a local target-device recording"
        )
    cluster_reasons = []
    cluster_minimums = (
        ("speaker", len(speakers), protocol["minimum_speaker_clusters"]),
        ("session", len(sessions), protocol["minimum_session_clusters"]),
        ("speaker-session", len(joint_clusters), protocol["minimum_joint_clusters"]),
    )
    for label, actual, minimum in cluster_minimums:
        if actual < minimum:
            reason = f"{label} cluster shortfall: {actual} < {minimum}"
            cluster_reasons.append(reason)
            qualification_reasons.append(reason)
    annotation_counts = {
        "number": sum(int(item["number_total"]) for item in sentence_errors),
        "proper_name": sum(int(item["proper_name_total"]) for item in sentence_errors),
    }
    profile = protocol["profile"]
    if profile in {"exploration", "production_confirmation"}:
        preregistration = protocol["preregistration"]
        clip_count = len(sentence_errors)
        initial_pcm = preregistration["initial_unique_pcm"]
        maximum_pcm = preregistration["maximum_unique_pcm"]
        increment = preregistration["sequential_extension"]["increment_unique_pcm"]
        allowed_pcm_counts = (
            {initial_pcm}
            if not preregistration["sequential_extension"]["enabled"]
            else set(range(initial_pcm, maximum_pcm + 1, increment))
        )
        if clip_count not in allowed_pcm_counts:
            qualification_reasons.append(
                f"{profile} unique PCM count must be one of {sorted(allowed_pcm_counts)}"
            )
        if profile == "exploration":
            expected_speakers = preregistration["initial_speakers"]
        else:
            extensions = max(0, (clip_count - initial_pcm) // max(increment, 1))
            expected_speakers = (
                preregistration["initial_speakers"]
                + extensions
                * preregistration["sequential_extension"]["increment_speakers"]
            )
        if len(speakers) != expected_speakers:
            qualification_reasons.append(
                f"{profile} speaker count must be {expected_speakers}"
            )
        expected_sessions = expected_speakers * preregistration["sessions_per_speaker"]
        if len(sessions) != expected_sessions:
            qualification_reasons.append(
                f"{profile} session count must be {expected_sessions}"
            )
        if sentence_errors:
            speaker_counts = {
                speaker: sum(
                    item["speaker_cluster_id"] == speaker for item in sentence_errors
                )
                for speaker in speakers
            }
            largest_fraction = max(speaker_counts.values()) / len(sentence_errors)
            if largest_fraction > preregistration["maximum_single_speaker_fraction"]:
                qualification_reasons.append(
                    "single-speaker share exceeds the preregistered maximum"
                )
        for annotation_kind, minimum in preregistration[
            "proper_name_number_minimums"
        ].items():
            if annotation_counts[annotation_kind] < minimum:
                qualification_reasons.append(
                    f"{annotation_kind} annotation coverage shortfall: "
                    f"{annotation_counts[annotation_kind]} < {minimum}"
                )
    clip_ids = [item["clip_id"] for item in sentence_errors]
    reference_lengths = {
        item["clip_id"]: item["reference_characters"] for item in sentence_errors
    }
    pcm_hashes = {item["clip_id"]: item["pcm_sha256"] for item in sentence_errors}
    return {
        "clip_count": len(sentence_errors),
        "condition_counts": condition_counts,
        "condition_speaker_counts": condition_speaker_counts,
        "condition_session_counts": condition_session_counts,
        "source_kind_counts": source_counts,
        "annotation_counts": annotation_counts,
        "protocol_profile": profile,
        "selection_authority": protocol["selection_authority"],
        "speaker_cluster_count": len(speakers),
        "session_cluster_count": len(sessions),
        "joint_cluster_count": len(joint_clusters),
        "clip_set_sha256": canonical_sha256(sorted(clip_ids)),
        "reference_lengths_sha256": canonical_sha256(reference_lengths),
        "pcm_set_sha256": canonical_sha256(pcm_hashes),
        "protocol_qualification": {
            "eligible": not qualification_reasons,
            "reasons": qualification_reasons,
        },
        "generalizability": {
            "eligible": not cluster_reasons,
            "bootstrap_unit": protocol["bootstrap_unit"],
            "reasons": cluster_reasons,
        },
    }


def score_model(
    manifest: Any,
    reference_set: Mapping[str, Any],
    model_output: Mapping[str, Any],
    normalization: NormalizationConfig,
    public_plan: Mapping[str, Any],
    dataset_protocol: Mapping[str, Any],
    selection_config: Mapping[str, Any],
    *,
    exploration_evidence: Optional[VerifiedExplorationEvidence] = None,
    exploration_manifest: Optional[Mapping[str, Any]] = None,
    exploration_public_plan: Optional[Mapping[str, Any]] = None,
    exploration_dataset_protocol: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    protocol_document = validate_dataset_protocol(dataset_protocol)
    manifest_document = _manifest_document_for_profile(
        manifest,
        protocol_document["profile"],
    )
    protocol = validate_dataset_protocol(
        protocol_document,
        manifest,
        exploration_evidence=exploration_evidence,
        exploration_manifest=exploration_manifest,
        exploration_public_plan=exploration_public_plan,
        exploration_dataset_protocol=exploration_dataset_protocol,
    )
    plan = validate_public_plan(
        public_plan,
        manifest,
        exploration_evidence=exploration_evidence,
    )
    output = validate_model_output(
        model_output,
        plan,
        manifest,
        protocol,
        exploration_evidence=exploration_evidence,
        exploration_manifest=exploration_manifest,
        exploration_public_plan=exploration_public_plan,
        exploration_dataset_protocol=exploration_dataset_protocol,
    )
    expected = {item["clip_id"] for item in manifest_document["clips"]}
    references = validate_reference_set(
        reference_set,
        expected_dataset_id=manifest_document["dataset_id"],
        expected_clip_ids=expected,
    )
    fingerprints = plan["contract_fingerprints"]
    if canonical_sha256(protocol) != fingerprints["dataset_protocol_sha256"]:
        raise ContractError("dataset protocol fingerprint must match the blind run")
    if normalization.fingerprint() != fingerprints["normalization_sha256"]:
        raise ContractError("normalization fingerprint must match the blind run")
    if canonical_sha256(selection_config) != fingerprints["selection_config_sha256"]:
        raise ContractError("selection config fingerprint must match the blind run")
    if protocol["profile"] in {"exploration", "production_confirmation"}:
        bootstrap = protocol["preregistration"]["bootstrap"]
        if (
            selection_config.get("bootstrap_samples") != bootstrap["samples"]
            or selection_config.get("confidence_level") != bootstrap["confidence_level"]
            or selection_config.get("bootstrap_seed") != bootstrap["seed"]
            or selection_config.get("practical_cer_delta")
            != protocol["preregistration"]["replacement_rules"][
                "minimum_absolute_cer_improvement"
            ]
        ):
            raise ContractError(
                "selection config must match the frozen formal bootstrap and CER thresholds"
            )

    manifest_clips = {
        item["clip_id"]: item for item in manifest_document["clips"]
    }
    clips = [manifest_clips[item["clip_id"]] for item in output["predictions"]]
    references_by_clip = {item["clip_id"]: item for item in references["references"]}
    predictions_by_clip = {item["clip_id"]: item for item in output["predictions"]}
    _validate_formal_condition_definitions(
        clips,
        references_by_clip,
        normalization,
        protocol,
    )
    metrics, sentence_errors = _score_subset(
        clips,
        references_by_clip,
        predictions_by_clip,
        normalization,
    )
    groups: dict[str, Any] = {}
    for condition in REQUIRED_SELECTION_CONDITIONS:
        subset_errors = [
            item for item in sentence_errors if condition in item["conditions"]
        ]
        if subset_errors:
            group_metrics = _metrics_from_errors(subset_errors)
            groups[condition] = {
                key: value
                for key, value in group_metrics.items()
                if key != "status_counts"
            }
    summary = _dataset_summary(sentence_errors, protocol)
    reference_set_sha256 = canonical_sha256(references)
    evaluation_contract = {
        "blind_evaluation": True,
        "decoder_contract_id": DECODER_CONTRACT_ID,
        "pcm_contract_id": manifest_document["pcm_contract_id"],
        "input_transform_id": INPUT_TRANSFORM_ID,
        "randomization_algorithm": plan["randomization"]["algorithm"],
        "private_map_sha256": plan["randomization"]["private_map_sha256"],
        "manifest_sha256": canonical_sha256(manifest_document),
        "reference_set_sha256": reference_set_sha256,
        "public_plan_sha256": canonical_sha256(plan),
        "dataset_protocol_sha256": canonical_sha256(protocol),
        "normalization_sha256": normalization.fingerprint(),
        "selection_config_sha256": canonical_sha256(selection_config),
        "registry_sha256": fingerprints["registry_sha256"],
        "clip_set_sha256": summary["clip_set_sha256"],
        "reference_lengths_sha256": summary["reference_lengths_sha256"],
        "pcm_set_sha256": summary["pcm_set_sha256"],
        "prediction_order_sha256": canonical_sha256(
            [item["clip_id"] for item in sentence_errors]
        ),
        "decoder_plan_sha256": output["decoder_plan_sha256"],
        "accuracy_output_sha256": canonical_sha256(output),
    }
    report = {
        "schema_version": "1.0",
        "run_id": output["run_id"],
        "dataset_id": manifest_document["dataset_id"],
        "model_alias": output["model_alias"],
        "normalization": {
            "profile_id": normalization.profile_id,
            "config": normalization.to_dict(),
            "sha256": normalization.fingerprint(),
        },
        "evaluation_contract": evaluation_contract,
        "dataset_summary": summary,
        "metrics": metrics,
        "groups": groups,
        "sentence_errors": sentence_errors,
    }
    validate_score_report(report)
    _revalidate_formal_snapshot_bindings(
        manifest,
        protocol["profile"],
        exploration_evidence=exploration_evidence,
    )
    return report


_METRIC_KEYS = {
    "cer",
    "substitutions",
    "deletions",
    "insertions",
    "reference_characters",
    "sentence_count",
    "sentence_exact_rate",
    "head_deleted_chars",
    "tail_deleted_chars",
    "number_recall",
    "proper_name_recall",
    "chinese_cer",
    "latin_cer",
    "latin_wer",
    "status_counts",
}
_GROUP_METRIC_KEYS = _METRIC_KEYS - {"status_counts"}
_SENTENCE_KEYS = {
    "clip_id",
    "cer",
    "substitutions",
    "deletions",
    "insertions",
    "reference_characters",
    "head_deleted_chars",
    "tail_deleted_chars",
    "chinese_edits",
    "chinese_reference_characters",
    "latin_edits",
    "latin_reference_characters",
    "latin_word_edits",
    "latin_reference_words",
    "number_total",
    "number_hits",
    "proper_name_total",
    "proper_name_hits",
    "missing_annotation_ids",
    "pcm_sha256",
    "conditions",
    "source_kind",
    "speaker_cluster_id",
    "session_cluster_id",
    "status",
}


def _numbers_equal(left: Any, right: Any) -> bool:
    if left is None or right is None:
        return left is right
    if isinstance(left, bool) or isinstance(right, bool):
        return left is right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return math.isclose(float(left), float(right), rel_tol=0, abs_tol=1e-12)
    return left == right


def _validate_metric_block(
    value: Any,
    expected: Mapping[str, Any],
    *,
    group: bool,
    context: str,
) -> None:
    metrics = _require_mapping(value, context)
    keys = _GROUP_METRIC_KEYS if group else _METRIC_KEYS
    _exact_keys(metrics, keys, context=context)
    for key in keys:
        if key == "status_counts":
            if metrics[key] != expected[key]:
                raise ContractError(f"{context}.{key} is internally inconsistent")
        elif not _numbers_equal(metrics[key], expected[key]):
            label = "CER" if key == "cer" else key
            raise ContractError(f"{context}.{label} is internally inconsistent")


def validate_score_report(data: Any) -> dict[str, Any]:
    report = dict(_require_mapping(data, "score report"))
    _exact_keys(
        report,
        {
            "schema_version",
            "run_id",
            "dataset_id",
            "model_alias",
            "normalization",
            "evaluation_contract",
            "dataset_summary",
            "metrics",
            "groups",
            "sentence_errors",
        },
        context="score report",
    )
    if report["schema_version"] != "1.0":
        raise ContractError("score report.schema_version must be 1.0")
    _require_anonymous_id(report["run_id"], "run", "score report.run_id")
    _require_anonymous_id(report["dataset_id"], "dataset", "score report.dataset_id")
    alias = _require_string(report["model_alias"], "score report.model_alias")
    if not re.fullmatch(r"M[0-9]{3}", alias):
        raise ContractError("score report.model_alias is invalid")

    normalization = _require_mapping(report["normalization"], "score report.normalization")
    _exact_keys(
        normalization,
        {"profile_id", "config", "sha256"},
        context="score report.normalization",
    )
    config = NormalizationConfig.from_dict(dict(normalization["config"]))
    if normalization["profile_id"] != config.profile_id:
        raise ContractError("score report normalization profile is inconsistent")
    if normalization["sha256"] != config.fingerprint():
        raise ContractError("score report normalization sha256 is inconsistent")

    evaluation = _require_mapping(
        report["evaluation_contract"], "score report.evaluation_contract"
    )
    evaluation_keys = {
        "blind_evaluation",
        "decoder_contract_id",
        "pcm_contract_id",
        "input_transform_id",
        "randomization_algorithm",
        "private_map_sha256",
        "manifest_sha256",
        "reference_set_sha256",
        "public_plan_sha256",
        "dataset_protocol_sha256",
        "normalization_sha256",
        "selection_config_sha256",
        "registry_sha256",
        "clip_set_sha256",
        "reference_lengths_sha256",
        "pcm_set_sha256",
        "prediction_order_sha256",
        "decoder_plan_sha256",
        "accuracy_output_sha256",
    }
    _exact_keys(evaluation, evaluation_keys, context="score report.evaluation_contract")
    if evaluation["blind_evaluation"] is not True:
        raise ContractError("score report must prove a blind evaluation")
    if (
        evaluation["decoder_contract_id"] != DECODER_CONTRACT_ID
        or evaluation["input_transform_id"] != INPUT_TRANSFORM_ID
    ):
        raise ContractError("score report decoder/input transform contract is invalid")
    if evaluation["randomization_algorithm"] != "sha256-fisher-yates-v1":
        raise ContractError("score report randomization contract is invalid")
    for key in evaluation_keys - {
        "blind_evaluation",
        "decoder_contract_id",
        "pcm_contract_id",
        "input_transform_id",
        "randomization_algorithm",
    }:
        _require_sha256(evaluation[key], f"score report.evaluation_contract.{key}")
    if evaluation["normalization_sha256"] != normalization["sha256"]:
        raise ContractError("score report normalization fingerprints do not match")

    raw_errors = _require_list(
        report["sentence_errors"], "score report.sentence_errors", non_empty=True
    )
    sentence_errors: list[Mapping[str, Any]] = []
    seen: Set[str] = set()
    for index, raw_error in enumerate(raw_errors):
        context = f"score report.sentence_errors[{index}]"
        item = _require_mapping(raw_error, context)
        _exact_keys(item, _SENTENCE_KEYS, context=context)
        clip_id = _require_anonymous_id(item["clip_id"], "clip", f"{context}.clip_id")
        if clip_id in seen:
            raise ContractError("score report contains duplicate clip_id")
        seen.add(clip_id)
        for field in (
            "substitutions",
            "deletions",
            "insertions",
            "reference_characters",
            "head_deleted_chars",
            "tail_deleted_chars",
            "chinese_edits",
            "chinese_reference_characters",
            "latin_edits",
            "latin_reference_characters",
            "latin_word_edits",
            "latin_reference_words",
            "number_total",
            "number_hits",
            "proper_name_total",
            "proper_name_hits",
        ):
            _require_nonnegative_int(item[field], f"{context}.{field}")
        expected_cer = (
            item["substitutions"] + item["deletions"] + item["insertions"]
        ) / (item["reference_characters"] or 1)
        if not _numbers_equal(item["cer"], expected_cer):
            raise ContractError(f"{context}.CER is internally inconsistent")
        _require_sha256(item["pcm_sha256"], f"{context}.pcm_sha256")
        conditions = _require_list(item["conditions"], f"{context}.conditions", non_empty=True)
        if (
            len(conditions) != len(set(conditions))
            or not set(conditions).issubset(set(REQUIRED_SELECTION_CONDITIONS))
        ):
            raise ContractError(f"{context}.conditions is invalid")
        if item["source_kind"] not in SOURCE_KINDS:
            raise ContractError(f"{context}.source_kind is invalid")
        _require_anonymous_id(
            item["speaker_cluster_id"], "speaker", f"{context}.speaker_cluster_id"
        )
        _require_anonymous_id(
            item["session_cluster_id"], "session", f"{context}.session_cluster_id"
        )
        if item["status"] not in {"ok", "error"}:
            raise ContractError(f"{context}.status is invalid")
        missing = _require_list(
            item["missing_annotation_ids"], f"{context}.missing_annotation_ids"
        )
        for annotation_id in missing:
            _require_anonymous_id(annotation_id, "ann", f"{context}.missing annotation")
        if item["number_hits"] > item["number_total"] or item["proper_name_hits"] > item[
            "proper_name_total"
        ]:
            raise ContractError(f"{context} annotation hits exceed totals")
        sentence_errors.append(item)

    expected_metrics = _metrics_from_errors(sentence_errors)
    _validate_metric_block(
        report["metrics"],
        expected_metrics,
        group=False,
        context="score report.metrics",
    )
    groups = _require_mapping(report["groups"], "score report.groups")
    expected_group_names = {
        condition
        for condition in REQUIRED_SELECTION_CONDITIONS
        if any(condition in item["conditions"] for item in sentence_errors)
    }
    if set(groups) != expected_group_names:
        raise ContractError("score report groups do not match sentence conditions")
    for condition in sorted(expected_group_names):
        subset = [item for item in sentence_errors if condition in item["conditions"]]
        _validate_metric_block(
            groups[condition],
            _metrics_from_errors(subset),
            group=True,
            context=f"score report.groups.{condition}",
        )

    summary = _require_mapping(report["dataset_summary"], "score report.dataset_summary")
    summary_keys = {
        "clip_count",
        "condition_counts",
        "condition_speaker_counts",
        "condition_session_counts",
        "source_kind_counts",
        "annotation_counts",
        "protocol_profile",
        "selection_authority",
        "speaker_cluster_count",
        "session_cluster_count",
        "joint_cluster_count",
        "clip_set_sha256",
        "reference_lengths_sha256",
        "pcm_set_sha256",
        "protocol_qualification",
        "generalizability",
    }
    _exact_keys(summary, summary_keys, context="score report.dataset_summary")
    if summary["clip_count"] != len(sentence_errors):
        raise ContractError("score report dataset clip count is inconsistent")
    condition_counts = {
        condition: sum(condition in item["conditions"] for item in sentence_errors)
        for condition in REQUIRED_SELECTION_CONDITIONS
    }
    source_counts = {
        source: sum(item["source_kind"] == source for item in sentence_errors)
        for source in SOURCE_KINDS
    }
    if summary["condition_counts"] != condition_counts:
        raise ContractError("score report condition counts are inconsistent")
    expected_condition_speakers = {
        condition: len(
            {
                item["speaker_cluster_id"]
                for item in sentence_errors
                if condition in item["conditions"]
            }
        )
        for condition in REQUIRED_SELECTION_CONDITIONS
    }
    expected_condition_sessions = {
        condition: len(
            {
                item["session_cluster_id"]
                for item in sentence_errors
                if condition in item["conditions"]
            }
        )
        for condition in REQUIRED_SELECTION_CONDITIONS
    }
    if summary["condition_speaker_counts"] != expected_condition_speakers:
        raise ContractError("score report condition speaker counts are inconsistent")
    if summary["condition_session_counts"] != expected_condition_sessions:
        raise ContractError("score report condition session counts are inconsistent")
    if summary["source_kind_counts"] != source_counts:
        raise ContractError("score report source counts are inconsistent")
    if summary["annotation_counts"] != {
        "number": sum(item["number_total"] for item in sentence_errors),
        "proper_name": sum(item["proper_name_total"] for item in sentence_errors),
    }:
        raise ContractError("score report annotation counts are inconsistent")
    if summary["protocol_profile"] not in {
        "development_fixture",
        "exploration",
        "production_confirmation",
    }:
        raise ContractError("score report protocol profile is invalid")
    if summary["selection_authority"] not in {
        "development_screening_only",
        "elimination_only",
        "production_confirmation",
    }:
        raise ContractError("score report selection authority is invalid")
    if summary["speaker_cluster_count"] != len(
        {item["speaker_cluster_id"] for item in sentence_errors}
    ):
        raise ContractError("score report speaker cluster count is inconsistent")
    if summary["session_cluster_count"] != len(
        {item["session_cluster_id"] for item in sentence_errors}
    ):
        raise ContractError("score report session cluster count is inconsistent")
    if summary["joint_cluster_count"] != len(
        {
            (item["speaker_cluster_id"], item["session_cluster_id"])
            for item in sentence_errors
        }
    ):
        raise ContractError("score report joint cluster count is inconsistent")
    expected_hashes = {
        "clip_set_sha256": canonical_sha256(
            sorted(item["clip_id"] for item in sentence_errors)
        ),
        "reference_lengths_sha256": canonical_sha256(
            {
                item["clip_id"]: item["reference_characters"]
                for item in sentence_errors
            }
        ),
        "pcm_set_sha256": canonical_sha256(
            {item["clip_id"]: item["pcm_sha256"] for item in sentence_errors}
        ),
    }
    for field, expected_hash in expected_hashes.items():
        if summary[field] != expected_hash or evaluation[field] != expected_hash:
            raise ContractError(f"score report {field} is internally inconsistent")
    expected_order_hash = canonical_sha256(
        [item["clip_id"] for item in sentence_errors]
    )
    if evaluation["prediction_order_sha256"] != expected_order_hash:
        raise ContractError("score report prediction order fingerprint is inconsistent")
    for field in ("protocol_qualification", "generalizability"):
        value = _require_mapping(summary[field], f"score report.dataset_summary.{field}")
        _exact_keys(
            value,
            (
                {"eligible", "reasons"}
                if field == "protocol_qualification"
                else {"eligible", "bootstrap_unit", "reasons"}
            ),
            context=f"score report.dataset_summary.{field}",
        )
        if not isinstance(value["eligible"], bool):
            raise ContractError(f"score report {field}.eligible must be boolean")
        reasons = _require_list(value["reasons"], f"score report {field}.reasons")
        if not all(isinstance(item, str) and item for item in reasons):
            raise ContractError(f"score report {field}.reasons is invalid")
        if value["eligible"] != (not reasons):
            raise ContractError(f"score report {field} eligibility is inconsistent")
    if summary["generalizability"]["bootstrap_unit"] not in {
        "speaker_session_cluster",
        "speaker_nested_session_clip",
    }:
        raise ContractError("score report bootstrap unit is invalid")

    return report
