#!/usr/bin/env python3
"""Fail-closed preflight for the isolated Paraformer benchmark build."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from kittyecho_asr_bench.contracts import (
    ContractError,
    formal_benchmark_artifact_blockers,
)
from kittyecho_asr_bench.model_security import (
    load_download_freeze_receipt,
    load_trusted_model_registry,
)

MODEL_SHA256 = (
    "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7"
)
TOKENS_SHA256 = (
    "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
)
RUNTIME_SHA256 = (
    "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"
)
MODEL_RECEIPT_COMMIT_SHA256 = (
    "bc03d13ddb2184ab63ec3bf2a32ee8a856073ae54852774aaa68437dcc15e964"
)
TOKENS_RECEIPT_COMMIT_SHA256 = (
    "c13885a083472c35d99fa2b97dbf98de5e46ef88bdf89daf0ea3c9ceaf79326e"
)
RUNTIME_RECEIPT_COMMIT_SHA256 = (
    "18b8172f77a8bc4654804664b7a0fddfe5a42d44f36f21c12e9485e272257ba5"
)
REGISTRY_SNAPSHOT_SHA256 = (
    "ac813fa0649bbd1a554603ae4c5a5301c48b0d5839611ea59ffe32b6373db860"
)
EXPECTED_BLOCKERS: list[str] = []

_SPECS = (
    (
        "model.int8.onnx",
        "model.int8.onnx.receipt.json",
        223_385_835,
        MODEL_SHA256,
        MODEL_RECEIPT_COMMIT_SHA256,
    ),
    (
        "tokens.txt",
        "tokens.txt.receipt.json",
        75_756,
        TOKENS_SHA256,
        TOKENS_RECEIPT_COMMIT_SHA256,
    ),
    (
        "sherpa-onnx-1.13.3.aar",
        "sherpa-onnx-1.13.3.aar.receipt.json",
        57_044_841,
        RUNTIME_SHA256,
        RUNTIME_RECEIPT_COMMIT_SHA256,
    ),
)


def verify_build_inputs(
    *,
    repo_root: Path,
    model_receipt: Path,
    tokens_receipt: Path,
    runtime_receipt: Path,
    runtime_aar: Path,
) -> dict[str, object]:
    safe_repo_root = Path(repo_root).resolve()
    receipt_paths = tuple(
        _require_private_named_path(path, safe_repo_root, spec[1])
        for path, spec in zip(
            (model_receipt, tokens_receipt, runtime_receipt),
            _SPECS,
            strict=True,
        )
    )
    safe_runtime_aar = _require_private_named_path(
        runtime_aar,
        safe_repo_root,
        "sherpa-onnx-1.13.3.aar",
    )

    first = _load_and_validate(receipt_paths, safe_repo_root)
    if Path(first[2].measurement.path).resolve() != safe_runtime_aar:
        raise ContractError(
            "Paraformer runtime AAR property differs from its canonical receipt"
        )

    trusted = load_trusted_model_registry()
    blockers = formal_benchmark_artifact_blockers(
        trusted.document,
        "paraformer_int8",
        artifact_freeze_receipt_paths=receipt_paths,
        repo_root=safe_repo_root,
    )
    if blockers != EXPECTED_BLOCKERS:
        raise ContractError(
            "Paraformer build gate must contain no blockers"
        )

    second = _load_and_validate(receipt_paths, safe_repo_root)
    if tuple(_evidence_binding(item) for item in first) != tuple(
        _evidence_binding(item) for item in second
    ):
        raise ContractError(
            "Paraformer receipt or artifact changed during build preflight"
        )

    return {
        "model_id": "paraformer_int8",
        "status": "production_ready_verified_inputs",
        "registry_snapshot_sha256": REGISTRY_SNAPSHOT_SHA256,
        "model_sha256": MODEL_SHA256,
        "tokens_sha256": TOKENS_SHA256,
        "runtime_sha256": RUNTIME_SHA256,
        "model_receipt_commit_sha256": MODEL_RECEIPT_COMMIT_SHA256,
        "tokens_receipt_commit_sha256": TOKENS_RECEIPT_COMMIT_SHA256,
        "runtime_receipt_commit_sha256": RUNTIME_RECEIPT_COMMIT_SHA256,
    }


def _load_and_validate(
    receipt_paths: tuple[Path, ...],
    repo_root: Path,
) -> tuple[Any, ...]:
    loaded = []
    for receipt_path, spec in zip(receipt_paths, _SPECS, strict=True):
        filename, _receipt_name, size_bytes, sha256, receipt_sha256 = spec
        evidence = load_download_freeze_receipt(
            receipt_path,
            model_id="paraformer_int8",
            artifact_filename=filename,
            repo_root=repo_root,
        )
        if (
            evidence.model_id != "paraformer_int8"
            or evidence.artifact_filename != filename
            or evidence.measured_bytes != size_bytes
            or evidence.measured_sha256 != sha256
            or evidence.receipt_commit_sha256 != receipt_sha256
            or evidence.registry_sha256 != REGISTRY_SNAPSHOT_SHA256
        ):
            raise ContractError(
                f"Paraformer {filename} evidence differs from the frozen contract"
            )
        loaded.append(evidence)
    return tuple(loaded)


def _evidence_binding(evidence: Any) -> tuple[object, ...]:
    return (
        evidence.model_id,
        evidence.artifact_filename,
        evidence.measured_bytes,
        evidence.measured_sha256,
        evidence.receipt_commit_sha256,
        evidence.registry_sha256,
        str(Path(evidence.measurement.path).resolve()),
    )


def _require_private_named_path(
    path: Path,
    repo_root: Path,
    expected_name: str,
) -> Path:
    resolved = Path(path).expanduser().resolve()
    if resolved.name != expected_name:
        raise ContractError(
            f"Paraformer build input must use canonical {expected_name}"
        )
    if resolved == repo_root or repo_root in resolved.parents:
        raise ContractError("Paraformer build inputs must remain outside the repository")
    return resolved


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--model-receipt", type=Path, required=True)
    parser.add_argument("--tokens-receipt", type=Path, required=True)
    parser.add_argument("--runtime-receipt", type=Path, required=True)
    parser.add_argument("--runtime-aar", type=Path, required=True)
    return parser


def main() -> int:
    arguments = _parser().parse_args()
    result = verify_build_inputs(
        repo_root=arguments.repo_root,
        model_receipt=arguments.model_receipt,
        tokens_receipt=arguments.tokens_receipt,
        runtime_receipt=arguments.runtime_receipt,
        runtime_aar=arguments.runtime_aar,
    )
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
