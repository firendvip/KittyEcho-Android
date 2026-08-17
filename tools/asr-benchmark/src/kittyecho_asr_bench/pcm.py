from __future__ import annotations

import hashlib
import io
import json
import os
import stat
import tempfile
import time
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional

from .contracts import ContractError, validate_recording_manifest


@dataclass(frozen=True)
class PcmInfo:
    sample_rate_hz: int
    channels: int
    sample_width_bits: int
    frame_count: int
    duration_seconds: float
    sha256: str
    payload_sha256: str
    payload_bytes: int


@dataclass(frozen=True)
class _FrozenFileIdentity:
    path: Path
    st_dev: int
    st_ino: int
    sha256: str
    size_bytes: int
    mode: int
    nlink: int
    payload_sha256: str
    payload_bytes: int


@dataclass(frozen=True)
class _ValidatedWavRead:
    wav_bytes: bytes
    info: PcmInfo
    identity: _FrozenFileIdentity


_MAX_WAV_BYTES = 512 * 1024 * 1024
_READ_BLOCK_BYTES = 1024 * 1024
_SNAPSHOT_MODE = 0o400
_SNAPSHOT_LINK_SETTLE_ATTEMPTS = 20
_SNAPSHOT_LINK_SETTLE_SECONDS = 0.005


class _LinkCountTransition(ContractError):
    """A bounded-retry signal for concurrent atomic snapshot publication."""


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _safe_read_regular_file_with_stat(
    path: Path,
    *,
    context: str,
) -> tuple[bytes, os.stat_result]:
    candidate = Path(path)
    if not candidate.is_absolute():
        raise ContractError(f"{context} path must be absolute")
    if candidate.suffix.lower() != ".wav":
        raise ContractError(f"{context} must use a .wav filename")

    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    flags |= nofollow
    if nofollow == 0:
        try:
            if stat.S_ISLNK(candidate.lstat().st_mode):
                raise ContractError(f"{context} must not be a symlink")
        except FileNotFoundError as error:
            raise ContractError(
                f"{context} is not a readable WAV file"
            ) from error
        except OSError as error:
            raise ContractError(f"{context} cannot be inspected safely") from error
    try:
        descriptor = os.open(str(candidate), flags)
    except OSError as error:
        raise ContractError(
            f"{context} must be a readable WAV file and must not be a symlink"
        ) from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ContractError(f"{context} must be a regular WAV file")
        if before.st_size < 0 or before.st_size > _MAX_WAV_BYTES:
            raise ContractError(f"{context} exceeds the bounded WAV size")
        blocks: list[bytes] = []
        total = 0
        while True:
            block = os.read(descriptor, _READ_BLOCK_BYTES)
            if not block:
                break
            total += len(block)
            if total > _MAX_WAV_BYTES:
                raise ContractError(f"{context} exceeds the bounded WAV size")
            blocks.append(block)
        after = os.fstat(descriptor)
        stable_except_link_count = (
            before.st_dev == after.st_dev
            and before.st_ino == after.st_ino
            and before.st_mode == after.st_mode
            and before.st_size == after.st_size
            and total == after.st_size
        )
        if stable_except_link_count and before.st_nlink != after.st_nlink:
            raise _LinkCountTransition(
                f"{context} link count changed while it was being read"
            )
        if (
            before.st_dev != after.st_dev
            or before.st_ino != after.st_ino
            or before.st_mode != after.st_mode
            or before.st_size != after.st_size
            or total != after.st_size
        ):
            raise ContractError(f"{context} changed while it was being read")
        return b"".join(blocks), after
    finally:
        os.close(descriptor)


def _safe_read_regular_file(path: Path, *, context: str) -> bytes:
    wav_bytes, _metadata = _safe_read_regular_file_with_stat(
        path,
        context=context,
    )
    return wav_bytes


def _inspect_pcm16_wav_bytes(
    wav_bytes: bytes,
    *,
    allow_empty: bool,
    context: str,
) -> PcmInfo:
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as source:
            channels = source.getnchannels()
            sample_width = source.getsampwidth()
            sample_rate = source.getframerate()
            frame_count = source.getnframes()
            compression = source.getcomptype()
            payload = source.readframes(frame_count)
    except (OSError, EOFError, wave.Error) as error:
        raise ContractError(f"{context} has an invalid WAV container") from error
    if compression != "NONE":
        raise ContractError("WAV input must be uncompressed PCM")
    if sample_rate != 16_000:
        raise ContractError("WAV sample rate must be 16000 Hz")
    if channels != 1:
        raise ContractError("WAV input must be mono")
    if sample_width != 2:
        raise ContractError("WAV input must be PCM16")
    if frame_count == 0 and not allow_empty:
        raise ContractError("real WAV input must not be empty")
    return PcmInfo(
        sample_rate_hz=sample_rate,
        channels=channels,
        sample_width_bits=sample_width * 8,
        frame_count=frame_count,
        duration_seconds=frame_count / sample_rate,
        sha256=hashlib.sha256(wav_bytes).hexdigest(),
        payload_sha256=hashlib.sha256(payload).hexdigest(),
        payload_bytes=len(payload),
    )


def _read_validated_pcm16_wav(
    path: Path,
    *,
    expected_sha256: str,
    expected_payload_sha256: str,
    expected_payload_bytes: int,
    allow_empty: bool,
    context: str,
) -> tuple[bytes, PcmInfo]:
    read = _read_validated_pcm16_wav_with_identity(
        path,
        expected_sha256=expected_sha256,
        expected_payload_sha256=expected_payload_sha256,
        expected_payload_bytes=expected_payload_bytes,
        allow_empty=allow_empty,
        context=context,
    )
    return read.wav_bytes, read.info


def _read_validated_pcm16_wav_with_identity(
    path: Path,
    *,
    expected_sha256: str,
    expected_payload_sha256: str,
    expected_payload_bytes: int,
    allow_empty: bool,
    context: str,
) -> _ValidatedWavRead:
    candidate = Path(path)
    wav_bytes, metadata = _safe_read_regular_file_with_stat(
        candidate,
        context=context,
    )
    info = _inspect_pcm16_wav_bytes(
        wav_bytes,
        allow_empty=allow_empty,
        context=context,
    )
    if info.sha256 != expected_sha256:
        raise ContractError(f"{context} WAV hash mismatch")
    if (
        info.payload_sha256 != expected_payload_sha256
        or info.payload_bytes != expected_payload_bytes
    ):
        raise ContractError(f"{context} PCM payload hash mismatch")
    return _ValidatedWavRead(
        wav_bytes=wav_bytes,
        info=info,
        identity=_FrozenFileIdentity(
            path=candidate,
            st_dev=metadata.st_dev,
            st_ino=metadata.st_ino,
            sha256=info.sha256,
            size_bytes=metadata.st_size,
            mode=stat.S_IMODE(metadata.st_mode),
            nlink=metadata.st_nlink,
            payload_sha256=info.payload_sha256,
            payload_bytes=info.payload_bytes,
        ),
    )


def inspect_pcm16_wav(path: Path, *, allow_empty: bool = False) -> PcmInfo:
    candidate = Path(path)
    wav_bytes = _safe_read_regular_file(candidate, context="PCM input")
    return _inspect_pcm16_wav_bytes(
        wav_bytes,
        allow_empty=allow_empty,
        context="PCM input",
    )


def validate_manifest_pcm(
    manifest: Mapping[str, Any],
    repo_root: Path,
    *,
    fixture_root: Optional[Path] = None,
) -> dict[str, PcmInfo]:
    validated_manifest = validate_recording_manifest(manifest, repo_root)
    results: dict[str, PcmInfo] = {}
    resolved_fixture_root = Path(fixture_root).resolve() if fixture_root is not None else None
    for clip in validated_manifest["clips"]:
        path = Path(clip["audio_path"])
        synthetic = clip["source"]["kind"] == "synthetic_fixture"
        if synthetic:
            resolved_path = path.resolve()
            if resolved_fixture_root is None or not _is_relative_to(
                resolved_path,
                resolved_fixture_root,
            ):
                raise ContractError("synthetic audio must remain under the declared fixture root")
        _wav_bytes, info = _read_validated_pcm16_wav(
            path,
            expected_sha256=clip["audio_sha256"],
            expected_payload_sha256=clip["pcm_payload_sha256"],
            expected_payload_bytes=clip["pcm_payload_bytes"],
            allow_empty=synthetic,
            context=f"canonical PCM for {clip['clip_id']}",
        )
        results[clip["clip_id"]] = info
    return results


def _prepare_snapshot_root(
    snapshot_root: Path,
    repo_root: Path,
    *,
    create: bool,
) -> Path:
    candidate = Path(snapshot_root)
    if not candidate.is_absolute():
        raise ContractError("snapshot_root must be an absolute path")
    resolved_repo = Path(repo_root).resolve()
    prospective = candidate.resolve()
    if _is_relative_to(prospective, resolved_repo):
        raise ContractError("snapshot_root must remain outside the repository")
    created = False
    try:
        if candidate.exists():
            if stat.S_ISLNK(candidate.lstat().st_mode) or not candidate.is_dir():
                raise ContractError("snapshot_root must be a non-symlink directory")
        elif not create:
            raise ContractError("snapshot_root is missing")
        else:
            try:
                candidate.mkdir(parents=True, mode=0o700)
                created = True
            except FileExistsError:
                if (
                    stat.S_ISLNK(candidate.lstat().st_mode)
                    or not candidate.is_dir()
                ):
                    raise ContractError(
                        "snapshot_root must be a non-symlink directory"
                    )
    except ContractError:
        raise
    except OSError as error:
        raise ContractError("snapshot_root cannot be created safely") from error
    resolved = candidate.resolve()
    if _is_relative_to(resolved, resolved_repo):
        raise ContractError("snapshot_root must remain outside the repository")
    if created:
        try:
            resolved.chmod(0o700)
        except OSError as error:
            raise ContractError("snapshot_root permissions cannot be tightened") from error
    return resolved


def _fsync_directory(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(str(directory), flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _validate_snapshot_file(
    path: Path,
    *,
    expected_sha256: str,
    expected_payload_sha256: str,
    expected_payload_bytes: int,
    allow_empty: bool,
    context: str,
) -> PcmInfo:
    info, _identity = _verify_frozen_snapshot(
        path,
        expected_sha256=expected_sha256,
        expected_payload_sha256=expected_payload_sha256,
        expected_payload_bytes=expected_payload_bytes,
        allow_empty=allow_empty,
        context=context,
    )
    return info


def _verify_frozen_snapshot(
    path: Path,
    *,
    expected_sha256: str,
    expected_payload_sha256: str,
    expected_payload_bytes: int,
    allow_empty: bool,
    context: str,
    expected_identity: Optional[_FrozenFileIdentity] = None,
) -> tuple[PcmInfo, _FrozenFileIdentity]:
    """Verify snapshot content and every approved metadata field on one fd."""
    for attempt in range(_SNAPSHOT_LINK_SETTLE_ATTEMPTS):
        try:
            read = _read_validated_pcm16_wav_with_identity(
                path,
                expected_sha256=expected_sha256,
                expected_payload_sha256=expected_payload_sha256,
                expected_payload_bytes=expected_payload_bytes,
                allow_empty=allow_empty,
                context=context,
            )
        except _LinkCountTransition:
            if attempt + 1 < _SNAPSHOT_LINK_SETTLE_ATTEMPTS:
                time.sleep(_SNAPSHOT_LINK_SETTLE_SECONDS)
                continue
            raise ContractError(
                f"{context} link count did not stabilize"
            ) from None
        identity = read.identity
        if identity.mode != _SNAPSHOT_MODE:
            raise ContractError(f"{context} permissions are not read-only")
        if identity.nlink == 1:
            if expected_identity is not None and identity != expected_identity:
                raise ContractError(
                    f"{context} snapshot identity changed after verification"
                )
            return read.info, identity
        if attempt + 1 < _SNAPSHOT_LINK_SETTLE_ATTEMPTS:
            time.sleep(_SNAPSHOT_LINK_SETTLE_SECONDS)
    raise ContractError(f"{context} must not share its inode")


def _publish_snapshot_bytes(
    snapshot_root: Path,
    wav_bytes: bytes,
    info: PcmInfo,
) -> Path:
    if (
        len(info.sha256) != 64
        or any(character not in "0123456789abcdef" for character in info.sha256)
    ):
        raise ContractError("snapshot content digest is invalid")
    target = snapshot_root / f"{info.sha256}.wav"
    if target.parent != snapshot_root:
        raise ContractError("snapshot path escaped snapshot_root")

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".snapshot-",
        suffix=".tmp",
        dir=str(snapshot_root),
    )
    temporary = Path(temporary_name)
    published = False
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            descriptor = -1
            output.write(wav_bytes)
            output.flush()
            os.fsync(output.fileno())
            os.fchmod(output.fileno(), _SNAPSHOT_MODE)
        try:
            # Publish the fully synced inode atomically without replacing a snapshot.
            os.link(
                str(temporary),
                str(target),
                follow_symlinks=False,
            )
            published = True
        except FileExistsError:
            pass
        if published:
            temporary.unlink()
            _fsync_directory(snapshot_root)
        _verify_frozen_snapshot(
            target,
            expected_sha256=info.sha256,
            expected_payload_sha256=info.payload_sha256,
            expected_payload_bytes=info.payload_bytes,
            allow_empty=info.frame_count == 0,
            context="content-addressed snapshot",
        )
        _fsync_directory(snapshot_root)
        return target
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _snapshot_manifest_pcm(
    manifest: Mapping[str, Any],
    repo_root: Path,
    snapshot_root: Path,
    *,
    fixture_root: Optional[Path] = None,
) -> tuple[dict[str, Any], Path]:
    frozen, resolved_snapshot_root, _identities = (
        _snapshot_manifest_pcm_with_evidence(
            manifest,
            repo_root,
            snapshot_root,
            fixture_root=fixture_root,
        )
    )
    return frozen, resolved_snapshot_root


def _snapshot_manifest_pcm_with_evidence(
    manifest: Mapping[str, Any],
    repo_root: Path,
    snapshot_root: Path,
    *,
    fixture_root: Optional[Path] = None,
) -> tuple[dict[str, Any], Path, tuple[_FrozenFileIdentity, ...]]:
    """Freeze verified source WAV bytes into a repository-external snapshot."""
    validated_manifest = validate_recording_manifest(manifest, repo_root)
    resolved_snapshot_root = _prepare_snapshot_root(
        snapshot_root,
        repo_root,
        create=True,
    )
    resolved_fixture_root = (
        Path(fixture_root).resolve() if fixture_root is not None else None
    )
    frozen = json.loads(json.dumps(validated_manifest))
    frozen_identities: list[_FrozenFileIdentity] = []
    for clip in frozen["clips"]:
        source_path = Path(clip["audio_path"])
        synthetic = clip["source"]["kind"] == "synthetic_fixture"
        if synthetic:
            resolved_source = source_path.resolve()
            if resolved_fixture_root is None or not _is_relative_to(
                resolved_source,
                resolved_fixture_root,
            ):
                raise ContractError(
                    "synthetic audio must remain under the declared fixture root"
                )
        source_read = _read_validated_pcm16_wav_with_identity(
            source_path,
            expected_sha256=clip["audio_sha256"],
            expected_payload_sha256=clip["pcm_payload_sha256"],
            expected_payload_bytes=clip["pcm_payload_bytes"],
            allow_empty=synthetic,
            context=f"source WAV for {clip['clip_id']}",
        )
        frozen_path = _publish_snapshot_bytes(
            resolved_snapshot_root,
            source_read.wav_bytes,
            source_read.info,
        )
        _snapshot_info, snapshot_identity = _verify_frozen_snapshot(
            frozen_path,
            expected_sha256=source_read.info.sha256,
            expected_payload_sha256=source_read.info.payload_sha256,
            expected_payload_bytes=source_read.info.payload_bytes,
            allow_empty=source_read.info.frame_count == 0,
            context=f"frozen snapshot for {clip['clip_id']}",
        )
        if (
            source_read.identity.st_dev == snapshot_identity.st_dev
            and source_read.identity.st_ino == snapshot_identity.st_ino
        ):
            raise ContractError(
                "source WAV and published snapshot must not share the same inode"
            )
        clip["audio_path"] = str(frozen_path)
        frozen_identities.append(snapshot_identity)
    _validate_snapshot_manifest_pcm(
        frozen,
        repo_root,
        resolved_snapshot_root,
        expected_identities=tuple(frozen_identities),
    )
    return frozen, resolved_snapshot_root, tuple(frozen_identities)


def _validate_snapshot_manifest_pcm(
    manifest: Mapping[str, Any],
    repo_root: Path,
    snapshot_root: Path,
    *,
    expected_identities: Optional[tuple[_FrozenFileIdentity, ...]] = None,
) -> dict[str, PcmInfo]:
    """Re-open every frozen snapshot with no-follow semantics before formal use."""
    validated_manifest = validate_recording_manifest(manifest, repo_root)
    resolved_snapshot_root = _prepare_snapshot_root(
        snapshot_root,
        repo_root,
        create=False,
    )
    identities_by_path: Optional[dict[Path, _FrozenFileIdentity]] = None
    if expected_identities is not None:
        identities_by_path = {
            identity.path: identity for identity in expected_identities
        }
        if len(identities_by_path) != len(expected_identities):
            raise ContractError("frozen snapshot identity set contains duplicates")
    results: dict[str, PcmInfo] = {}
    seen_paths: set[Path] = set()
    for clip in validated_manifest["clips"]:
        expected_path = (
            resolved_snapshot_root / f"{clip['audio_sha256']}.wav"
        )
        actual_path = Path(clip["audio_path"])
        if actual_path != expected_path:
            raise ContractError(
                f"snapshot path mismatch for {clip['clip_id']}"
            )
        expected_identity = (
            identities_by_path.get(actual_path)
            if identities_by_path is not None
            else None
        )
        if identities_by_path is not None and expected_identity is None:
            raise ContractError(
                f"snapshot identity is missing for {clip['clip_id']}"
            )
        info, _actual_identity = _verify_frozen_snapshot(
            actual_path,
            expected_sha256=clip["audio_sha256"],
            expected_payload_sha256=clip["pcm_payload_sha256"],
            expected_payload_bytes=clip["pcm_payload_bytes"],
            allow_empty=clip["source"]["kind"] == "synthetic_fixture",
            context=f"snapshot WAV for {clip['clip_id']}",
            expected_identity=expected_identity,
        )
        results[clip["clip_id"]] = info
        seen_paths.add(actual_path)
    if identities_by_path is not None and seen_paths != set(identities_by_path):
        raise ContractError("frozen snapshot identity set does not match manifest")
    return results
