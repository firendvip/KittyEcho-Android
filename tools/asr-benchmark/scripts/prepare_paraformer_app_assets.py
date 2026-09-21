#!/usr/bin/env python3
"""Verify frozen Paraformer inputs and publish generated Android assets."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import stat

from kittyecho_asr_bench.contracts import ContractError
from kittyecho_asr_bench.model_security import load_download_freeze_receipt


MODEL_FILENAME = "model.int8.onnx"
MODEL_BYTES = 223_385_835
MODEL_SHA256 = (
    "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7"
)
TOKENS_FILENAME = "tokens.txt"
TOKENS_BYTES = 75_756
TOKENS_SHA256 = (
    "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
)
ASSET_DIRECTORY = Path("models/paraformer")
COPY_BUFFER_BYTES = 1024 * 1024


def prepare_assets(
    *,
    repo_root: Path,
    model: Path,
    tokens: Path,
    model_receipt: Path,
    tokens_receipt: Path,
    output_dir: Path,
) -> None:
    safe_repo_root = _require_absolute_directory(repo_root, "repository root")
    safe_output = _require_generated_output(output_dir, safe_repo_root)
    specs = (
        (MODEL_FILENAME, model, model_receipt, MODEL_BYTES, MODEL_SHA256),
        (TOKENS_FILENAME, tokens, tokens_receipt, TOKENS_BYTES, TOKENS_SHA256),
    )

    verified: list[tuple[str, Path, int, str, object]] = []
    for filename, source, receipt, expected_bytes, expected_sha256 in specs:
        safe_source = _require_external_named_file(
            source,
            safe_repo_root,
            filename,
            "Paraformer artifact",
        )
        safe_receipt = _require_external_named_file(
            receipt,
            safe_repo_root,
            f"{filename}.receipt.json",
            "Paraformer freeze receipt",
        )
        evidence = load_download_freeze_receipt(
            safe_receipt,
            model_id="paraformer_int8",
            artifact_filename=filename,
            repo_root=safe_repo_root,
        )
        if evidence.measurement.path.resolve(strict=True) != safe_source:
            raise ContractError(f"{filename} differs from its frozen receipt path")
        if (
            evidence.measured_bytes != expected_bytes
            or evidence.measured_sha256 != expected_sha256
        ):
            raise ContractError(f"{filename} differs from the app frozen contract")
        verified.append((filename, safe_source, expected_bytes, expected_sha256, evidence))

    temporary = safe_output.with_name(f".{safe_output.name}.tmp")
    _delete_generated_tree(temporary, safe_repo_root)
    temporary.mkdir(parents=True, mode=0o700)
    asset_root = temporary / ASSET_DIRECTORY
    asset_root.mkdir(parents=True, mode=0o700)
    try:
        for filename, source, expected_bytes, expected_sha256, evidence in verified:
            _copy_verified(
                source=source,
                destination=asset_root / filename,
                expected_bytes=expected_bytes,
                expected_sha256=expected_sha256,
                expected_identity=evidence.measurement,
            )
        _fsync_directory(asset_root)
        _fsync_directory(temporary)
        _delete_generated_tree(safe_output, safe_repo_root)
        os.rename(temporary, safe_output)
        _fsync_directory(safe_output.parent)
    except BaseException:
        _delete_generated_tree(temporary, safe_repo_root)
        raise


def _copy_verified(
    *,
    source: Path,
    destination: Path,
    expected_bytes: int,
    expected_sha256: str,
    expected_identity: object,
) -> None:
    source_fd = os.open(
        source,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    destination_fd = -1
    try:
        before = os.fstat(source_fd)
        _require_regular_single_link(before, source.name)
        _require_receipt_identity(before, expected_identity, source.name)
        destination_fd = os.open(
            destination,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        digest = hashlib.sha256()
        copied = 0
        with os.fdopen(destination_fd, "wb", closefd=False) as output:
            while True:
                block = os.read(source_fd, COPY_BUFFER_BYTES)
                if not block:
                    break
                output.write(block)
                digest.update(block)
                copied += len(block)
            output.flush()
            os.fsync(output.fileno())
        after = os.fstat(source_fd)
        if _identity(before) != _identity(after):
            raise ContractError(f"{source.name} changed while assets were generated")
        if copied != expected_bytes or digest.hexdigest() != expected_sha256:
            raise ContractError(f"{source.name} failed the post-copy frozen contract")
        destination_stat = os.fstat(destination_fd)
        _require_regular_single_link(destination_stat, destination.name)
        if destination_stat.st_size != expected_bytes:
            raise ContractError(f"generated {destination.name} has the wrong byte count")
    finally:
        if destination_fd >= 0:
            os.close(destination_fd)
        os.close(source_fd)


def _require_receipt_identity(value: os.stat_result, expected: object, name: str) -> None:
    actual = (
        value.st_dev,
        value.st_ino,
        value.st_size,
        stat.S_IMODE(value.st_mode),
        value.st_nlink,
    )
    frozen = (
        expected.st_dev,
        expected.st_ino,
        expected.size_bytes,
        expected.mode,
        expected.nlink,
    )
    if actual != frozen:
        raise ContractError(f"{name} identity differs from its frozen receipt")


def _identity(value: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mode,
        value.st_nlink,
    )


def _require_regular_single_link(value: os.stat_result, name: str) -> None:
    if not stat.S_ISREG(value.st_mode) or value.st_nlink != 1:
        raise ContractError(f"{name} must be a single-link regular file")


def _require_absolute_directory(path: Path, context: str) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError(f"{context} must be absolute")
    resolved = candidate.resolve(strict=True)
    if not resolved.is_dir():
        raise ContractError(f"{context} must be a directory")
    return resolved


def _require_external_named_file(
    path: Path,
    repo_root: Path,
    expected_name: str,
    context: str,
) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute() or candidate.name != expected_name:
        raise ContractError(f"{context} must be the absolute {expected_name}")
    identity = candidate.lstat()
    if not stat.S_ISREG(identity.st_mode) or identity.st_nlink != 1:
        raise ContractError(f"{context} must be a single-link non-symlink regular file")
    resolved = candidate.resolve(strict=True)
    if resolved == repo_root or repo_root in resolved.parents:
        raise ContractError(f"{context} must remain outside the repository")
    return resolved


def _require_generated_output(path: Path, repo_root: Path) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError("generated asset output must be absolute")
    resolved = candidate.resolve(strict=False)
    expected = (repo_root / "app/build/generated/assets/paraformer").resolve(strict=False)
    if resolved != expected:
        raise ContractError("generated asset output must be the fixed app build directory")
    return resolved


def _delete_generated_tree(path: Path, repo_root: Path) -> None:
    if not path.exists() and not path.is_symlink():
        return
    expected_parent = (repo_root / "app/build/generated/assets").resolve(strict=False)
    if path.parent.resolve(strict=False) != expected_parent:
        raise ContractError("refusing to delete outside generated assets")
    if path.is_symlink() or not path.is_dir():
        path.unlink()
    else:
        shutil.rmtree(path)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        value = os.fstat(descriptor)
        if not stat.S_ISDIR(value.st_mode):
            raise ContractError(f"{path.name} must be a directory")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--tokens", type=Path, required=True)
    parser.add_argument("--model-receipt", type=Path, required=True)
    parser.add_argument("--tokens-receipt", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    prepare_assets(
        repo_root=args.repo_root,
        model=args.model,
        tokens=args.tokens,
        model_receipt=args.model_receipt,
        tokens_receipt=args.tokens_receipt,
        output_dir=args.output_dir,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
