from __future__ import annotations

import copy
import os
import stat
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import kittyecho_asr_bench.pcm as benchmark_pcm
from kittyecho_asr_bench.contracts import ContractError
from kittyecho_asr_bench.pcm import (
    _snapshot_manifest_pcm,
    _validate_snapshot_manifest_pcm,
    inspect_pcm16_wav,
    validate_manifest_pcm,
)

from support import manifest_for, write_wav


class PcmContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_accepts_exact_16k_mono_pcm16_wav_and_reports_hash(self) -> None:
        path = self.private_root / "valid.wav"
        expected_sha = write_wav(path)
        info = inspect_pcm16_wav(path)
        self.assertEqual(info.sample_rate_hz, 16_000)
        self.assertEqual(info.channels, 1)
        self.assertEqual(info.sample_width_bits, 16)
        self.assertEqual(info.sha256, expected_sha)
        self.assertGreater(info.frame_count, 0)

    def test_rejects_wrong_rate_channels_width_and_empty_real_audio(self) -> None:
        cases = (
            ({"sample_rate": 8_000}, "16000"),
            ({"channels": 2}, "mono"),
            ({"sample_width": 1}, "PCM16"),
            ({"frame_count": 0}, "empty"),
        )
        for index, (kwargs, message) in enumerate(cases):
            with self.subTest(kwargs=kwargs):
                path = self.private_root / f"invalid-{index}.wav"
                write_wav(path, **kwargs)
                with self.assertRaisesRegex(ContractError, message):
                    inspect_pcm16_wav(path)

    def test_safe_pcm_open_rejects_path_and_container_attacks(self) -> None:
        with self.assertRaisesRegex(ContractError, "absolute"):
            inspect_pcm16_wav(Path("relative.wav"))

        self.private_root.mkdir()
        wrong_suffix = self.private_root / "audio.bin"
        wrong_suffix.write_bytes(b"not-a-wav")
        with self.assertRaisesRegex(ContractError, r"\.wav"):
            inspect_pcm16_wav(wrong_suffix)

        with self.assertRaisesRegex(ContractError, "readable WAV"):
            inspect_pcm16_wav(self.private_root / "missing.wav")

        invalid = self.private_root / "invalid.wav"
        invalid.write_bytes(b"not-a-wav")
        with self.assertRaisesRegex(ContractError, "invalid WAV container"):
            inspect_pcm16_wav(invalid)

    def test_safe_pcm_open_rejects_oversize_and_read_time_change(self) -> None:
        path = self.private_root / "source.wav"
        write_wav(path)
        actual = path.stat()
        oversized = SimpleNamespace(
            st_mode=actual.st_mode,
            st_size=benchmark_pcm._MAX_WAV_BYTES + 1,
            st_dev=actual.st_dev,
            st_ino=actual.st_ino,
            st_nlink=actual.st_nlink,
        )
        with mock.patch.object(
            benchmark_pcm.os,
            "fstat",
            return_value=oversized,
        ):
            with self.assertRaisesRegex(ContractError, "bounded WAV size"):
                inspect_pcm16_wav(path)

        before = SimpleNamespace(
            st_mode=actual.st_mode,
            st_size=actual.st_size,
            st_dev=actual.st_dev,
            st_ino=actual.st_ino,
            st_nlink=actual.st_nlink,
        )
        after = SimpleNamespace(
            st_mode=actual.st_mode,
            st_size=actual.st_size + 1,
            st_dev=actual.st_dev,
            st_ino=actual.st_ino,
            st_nlink=actual.st_nlink,
        )
        with mock.patch.object(
            benchmark_pcm.os,
            "fstat",
            side_effect=[before, after],
        ):
            with self.assertRaisesRegex(ContractError, "changed while"):
                inspect_pcm16_wav(path)

    def test_header_only_fixture_is_allowed_only_when_explicit(self) -> None:
        path = self.private_root / "header-only.wav"
        write_wav(path, frame_count=0)
        info = inspect_pcm16_wav(path, allow_empty=True)
        self.assertEqual(info.frame_count, 0)

    def test_manifest_pcm_validation_rejects_hash_mismatch(self) -> None:
        path = self.private_root / "valid.wav"
        sha = write_wav(path)
        manifest = manifest_for(path, sha)
        validated = validate_manifest_pcm(manifest, self.repo_root)
        self.assertEqual(validated["clip_000000000000"].sha256, sha)

        manifest["clips"][0]["audio_sha256"] = "f" * 64
        with self.assertRaisesRegex(ContractError, "hash"):
            validate_manifest_pcm(manifest, self.repo_root)

    def test_synthetic_fixture_may_live_only_under_declared_fixture_root(self) -> None:
        fixture_root = self.repo_root / "fixtures"
        path = fixture_root / "synthetic.wav"
        sha = write_wav(path, frame_count=0)
        manifest = manifest_for(path, sha, clip_count=1)
        manifest["clips"][0]["source"]["kind"] = "synthetic_fixture"
        validated = validate_manifest_pcm(
            manifest,
            self.repo_root,
            fixture_root=fixture_root,
        )
        self.assertEqual(validated["clip_000000000000"].frame_count, 0)

        outside = self.private_root / "synthetic.wav"
        outside_sha = write_wav(outside, frame_count=0)
        manifest["clips"][0]["audio_path"] = str(outside)
        manifest["clips"][0]["audio_sha256"] = outside_sha
        with self.assertRaisesRegex(ContractError, "fixture root"):
            validate_manifest_pcm(manifest, self.repo_root, fixture_root=fixture_root)

    def test_content_addressed_snapshot_reuses_only_verified_read_only_content(
        self,
    ) -> None:
        source = self.private_root / "source.wav"
        sha = write_wav(source)
        manifest = manifest_for(source, sha)
        snapshot_root = self.private_root / "snapshots"

        frozen, resolved_root = _snapshot_manifest_pcm(
            manifest,
            self.repo_root,
            snapshot_root,
        )
        reused, _ = _snapshot_manifest_pcm(
            manifest,
            self.repo_root,
            snapshot_root,
        )
        snapshot = Path(frozen["clips"][0]["audio_path"])
        self.assertEqual(resolved_root, snapshot_root.resolve())
        self.assertEqual(snapshot, snapshot_root.resolve() / f"{sha}.wav")
        self.assertEqual(reused, frozen)
        self.assertEqual(stat.S_IMODE(snapshot.stat().st_mode), 0o400)

        source.unlink()
        _validate_snapshot_manifest_pcm(
            frozen,
            self.repo_root,
            snapshot_root,
        )
        snapshot.chmod(0o600)
        with self.assertRaisesRegex(ContractError, "read-only"):
            _validate_snapshot_manifest_pcm(
                frozen,
                self.repo_root,
                snapshot_root,
            )

    def test_snapshot_content_and_metadata_are_verified_from_one_fd(self) -> None:
        source = self.private_root / "same-fd-source.wav"
        sha = write_wav(source)
        manifest = manifest_for(source, sha)
        frozen, _ = _snapshot_manifest_pcm(
            manifest,
            self.repo_root,
            self.private_root / "same-fd-snapshots",
        )
        clip = frozen["clips"][0]
        snapshot = Path(clip["audio_path"])

        with mock.patch.object(
            benchmark_pcm.os,
            "stat",
            side_effect=AssertionError(
                "snapshot approval must not perform a second path stat"
            ),
        ):
            info = benchmark_pcm._validate_snapshot_file(
                snapshot,
                expected_sha256=clip["audio_sha256"],
                expected_payload_sha256=clip["pcm_payload_sha256"],
                expected_payload_bytes=clip["pcm_payload_bytes"],
                allow_empty=False,
                context="same-fd regression snapshot",
            )
        self.assertEqual(info.sha256, sha)

    def test_fd_checkpoint_never_approves_metadata_from_replacement_inode(
        self,
    ) -> None:
        source = self.private_root / "fd-window-source.wav"
        sha = write_wav(source)
        manifest = manifest_for(source, sha)
        frozen, _ = _snapshot_manifest_pcm(
            manifest,
            self.repo_root,
            self.private_root / "fd-window-snapshots",
        )
        clip = frozen["clips"][0]
        snapshot = Path(clip["audio_path"])
        original_inode = snapshot.stat().st_ino
        replacement = self.private_root / "fd-window-replacement.wav"
        replacement.write_bytes(snapshot.read_bytes())
        replacement.chmod(0o400)
        original_fstat = benchmark_pcm.os.fstat
        calls = 0

        def replace_path_after_content_fstat(descriptor: int) -> object:
            nonlocal calls
            metadata = original_fstat(descriptor)
            calls += 1
            if calls == 2:
                os.replace(replacement, snapshot)
            return metadata

        with mock.patch.object(
            benchmark_pcm.os,
            "fstat",
            side_effect=replace_path_after_content_fstat,
        ):
            _info, opened_identity = benchmark_pcm._verify_frozen_snapshot(
                snapshot,
                expected_sha256=clip["audio_sha256"],
                expected_payload_sha256=clip["pcm_payload_sha256"],
                expected_payload_bytes=clip["pcm_payload_bytes"],
                allow_empty=False,
                context="fd replacement-window snapshot",
            )
        self.assertEqual(opened_identity.st_ino, original_inode)
        self.assertNotEqual(snapshot.stat().st_ino, opened_identity.st_ino)
        with self.assertRaisesRegex(ContractError, "identity changed"):
            benchmark_pcm._verify_frozen_snapshot(
                snapshot,
                expected_sha256=clip["audio_sha256"],
                expected_payload_sha256=clip["pcm_payload_sha256"],
                expected_payload_bytes=clip["pcm_payload_bytes"],
                allow_empty=False,
                context="fd replacement-window snapshot",
                expected_identity=opened_identity,
            )

    def test_snapshot_root_and_snapshot_manifest_paths_fail_closed(self) -> None:
        source = self.private_root / "source.wav"
        sha = write_wav(source)
        manifest = manifest_for(source, sha)
        with self.assertRaisesRegex(ContractError, "absolute"):
            _snapshot_manifest_pcm(
                manifest,
                self.repo_root,
                Path("relative-snapshots"),
            )

        real_root = self.private_root / "real-snapshots"
        real_root.mkdir()
        symlink_root = self.private_root / "linked-snapshots"
        symlink_root.symlink_to(real_root, target_is_directory=True)
        with self.assertRaisesRegex(ContractError, "non-symlink"):
            _snapshot_manifest_pcm(
                manifest,
                self.repo_root,
                symlink_root,
            )

        file_root = self.private_root / "snapshot-root-file"
        file_root.write_text("not a directory", encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "directory"):
            _snapshot_manifest_pcm(
                manifest,
                self.repo_root,
                file_root,
            )

        hardlink_root = self.private_root / "hardlink-snapshots"
        hardlink_root.mkdir()
        hardlinked_snapshot = hardlink_root / f"{sha}.wav"
        os.link(source, hardlinked_snapshot)
        hardlinked_snapshot.chmod(0o400)
        with self.assertRaisesRegex(ContractError, "share its inode"):
            _snapshot_manifest_pcm(
                manifest,
                self.repo_root,
                hardlink_root,
            )

        snapshot_root = self.private_root / "snapshots"
        frozen, _ = _snapshot_manifest_pcm(
            manifest,
            self.repo_root,
            snapshot_root,
        )
        mismatched = copy.deepcopy(frozen)
        mismatched["clips"][0]["audio_path"] = str(source)
        with self.assertRaisesRegex(ContractError, "snapshot path mismatch"):
            _validate_snapshot_manifest_pcm(
                mismatched,
                self.repo_root,
                snapshot_root,
            )

    def test_concurrent_same_digest_snapshot_publish_is_idempotent(self) -> None:
        source = self.private_root / "source.wav"
        sha = write_wav(source)
        manifest = manifest_for(source, sha)
        snapshot_root = self.private_root / "concurrent-snapshots"

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [
                executor.submit(
                    _snapshot_manifest_pcm,
                    manifest,
                    self.repo_root,
                    snapshot_root,
                )
                for _ in range(2)
            ]
            results = [future.result() for future in futures]

        self.assertEqual(results[0], results[1])
        snapshots = [
            path
            for path in snapshot_root.iterdir()
            if not path.name.startswith(".snapshot-")
        ]
        expected_snapshots = {
            snapshot_root / f"{clip['audio_sha256']}.wav"
            for clip in manifest["clips"]
        }
        self.assertEqual(set(snapshots), expected_snapshots)
        self.assertEqual(list(snapshot_root.glob(".snapshot-*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
