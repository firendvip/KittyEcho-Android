from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import kittyecho_asr_bench.artifacts as benchmark_artifacts
from kittyecho_asr_bench.contracts import ContractError


class ArtifactReceiptTransactionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.receipt = self.root / "result.json"
        self.document = {
            "schema_version": "1.0",
            "run_id": "run_0123456789ab",
            "status": "prepared",
        }

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _commit(self, **overrides: object) -> dict[str, object]:
        from kittyecho_asr_bench.artifacts import commit_json_artifact

        arguments: dict[str, object] = {
            "artifact_kind": "test-report",
            "run_id": self.document["run_id"],
            "evidence_sha256": "a" * 64,
            "plan_sha256": "b" * 64,
            "validate_evidence": lambda: None,
        }
        arguments.update(overrides)
        return commit_json_artifact(
            self.receipt,
            self.document,
            **arguments,
        )

    def test_success_publishes_content_addressed_payload_then_valid_receipt(
        self,
    ) -> None:
        from kittyecho_asr_bench.artifacts import load_committed_json_artifact

        marker = self._commit()
        loaded, loaded_marker = load_committed_json_artifact(
            self.receipt,
            expected_kind="test-report",
            expected_evidence_sha256="a" * 64,
            expected_plan_sha256="b" * 64,
        )

        self.assertEqual(loaded, self.document)
        self.assertEqual(loaded_marker, marker)
        payload = Path(marker["artifact"]["path"])
        self.assertEqual(
            payload.parent,
            self.root.resolve() / ".kittyecho-asr-artifacts",
        )
        self.assertEqual(payload.name, f"{marker['artifact']['sha256']}.json")
        self.assertEqual(payload.stat().st_mode & 0o777, 0o400)
        self.assertEqual(self.receipt.stat().st_mode & 0o777, 0o400)

    def test_postvalidation_failure_keeps_orphan_blob_without_valid_receipt(
        self,
    ) -> None:
        calls = 0

        def reject_after_payload_publication() -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                raise ContractError("evidence changed after payload publication")

        with self.assertRaisesRegex(ContractError, "evidence changed"):
            self._commit(validate_evidence=reject_after_payload_publication)

        self.assertFalse(self.receipt.exists())
        blobs = list((self.root / ".kittyecho-asr-artifacts").glob("*.json"))
        self.assertEqual(len(blobs), 1)
        self.assertEqual(
            json.loads(blobs[0].read_text(encoding="utf-8")),
            self.document,
        )
        self.assertEqual(
            list(self.root.rglob("*.tmp-*"))
            + list(self.root.rglob(".artifact-*"))
            + list(self.root.rglob(".receipt-*")),
            [],
        )

    def test_postvalidation_replacement_is_never_deleted(self) -> None:
        foreign = b'{"owner":"external"}\n'
        calls = 0
        private_cleanup_paths: list[Path] = []
        original_private_cleanup = benchmark_artifacts._private_temp_unlink

        def replace_output_then_reject() -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                self.receipt.write_bytes(foreign)
                raise ContractError("evidence changed after payload publication")

        def record_private_cleanup(path: Path) -> None:
            private_cleanup_paths.append(Path(path))
            original_private_cleanup(path)

        with mock.patch.object(
            benchmark_artifacts,
            "_private_temp_unlink",
            side_effect=record_private_cleanup,
        ):
            with self.assertRaisesRegex(ContractError, "evidence changed"):
                self._commit(validate_evidence=replace_output_then_reject)

        self.assertEqual(self.receipt.read_bytes(), foreign)
        self.assertNotIn(
            self.receipt.resolve(),
            {path.resolve() for path in private_cleanup_paths},
        )
        self.assertTrue(
            all(
                path.name.startswith((".artifact-", ".receipt-"))
                or ".receipt-" in path.name
                for path in private_cleanup_paths
            )
        )

    def test_consumer_rejects_orphan_payload_and_replaced_payload_or_receipt(
        self,
    ) -> None:
        from kittyecho_asr_bench.artifacts import load_committed_json_artifact

        marker = self._commit()
        payload = Path(marker["artifact"]["path"])
        payload_bytes = payload.read_bytes()

        with self.assertRaisesRegex(ContractError, "receipt"):
            load_committed_json_artifact(payload)

        replacement = self.root / "replacement-payload.json"
        replacement.write_bytes(payload_bytes)
        replacement.chmod(0o400)
        os.replace(replacement, payload)
        with self.assertRaisesRegex(ContractError, "identity|inode|artifact"):
            load_committed_json_artifact(self.receipt)

        second_receipt = self.root / "second-result.json"
        from kittyecho_asr_bench.artifacts import commit_json_artifact

        commit_json_artifact(
            second_receipt,
            self.document,
            artifact_kind="test-report",
            run_id=self.document["run_id"],
            evidence_sha256="a" * 64,
            plan_sha256="b" * 64,
            validate_evidence=lambda: None,
        )
        receipt_bytes = second_receipt.read_bytes()
        receipt_replacement = self.root / "replacement-receipt.json"
        receipt_replacement.write_bytes(receipt_bytes)
        receipt_replacement.chmod(0o400)
        os.replace(receipt_replacement, second_receipt)
        with self.assertRaisesRegex(ContractError, "identity|inode|receipt"):
            load_committed_json_artifact(second_receipt)

    def test_receipt_and_payload_are_no_overwrite(self) -> None:
        from kittyecho_asr_bench.artifacts import load_committed_json_artifact

        marker = self._commit()
        receipt_before = self.receipt.read_bytes()
        payload = Path(marker["artifact"]["path"])
        payload_before = payload.read_bytes()

        with self.assertRaisesRegex(ContractError, "already exists|overwrite"):
            self._commit()

        self.assertEqual(self.receipt.read_bytes(), receipt_before)
        self.assertEqual(payload.read_bytes(), payload_before)
        loaded, _loaded_marker = load_committed_json_artifact(self.receipt)
        self.assertEqual(loaded, self.document)

    def test_commit_argument_and_transaction_store_attacks_fail_closed(
        self,
    ) -> None:
        from kittyecho_asr_bench.artifacts import commit_json_artifact

        valid = {
            "artifact_kind": "test-report",
            "run_id": self.document["run_id"],
            "evidence_sha256": "a" * 64,
            "plan_sha256": "b" * 64,
            "validate_evidence": lambda: None,
        }
        attacks = (
            ([], valid, "JSON object"),
            (self.document, {**valid, "artifact_kind": "../escape"}, "kind"),
            (self.document, {**valid, "run_id": "run_bad"}, "run_id"),
            (
                self.document,
                {**valid, "evidence_sha256": "not-a-hash"},
                "sha256",
            ),
            (
                self.document,
                {**valid, "plan_sha256": "not-a-hash"},
                "sha256",
            ),
            (
                {**self.document, "run_id": "run_ffffffffffff"},
                valid,
                "run_id",
            ),
            (
                self.document,
                {**valid, "validate_evidence": None},
                "callable",
            ),
        )
        for index, (document, arguments, message) in enumerate(attacks):
            with self.subTest(attack=index):
                with self.assertRaisesRegex(ContractError, message):
                    commit_json_artifact(
                        self.root / f"invalid-{index}.json",
                        document,
                        **arguments,
                    )
        with self.assertRaisesRegex(ContractError, "absolute"):
            commit_json_artifact(
                Path("relative-receipt.json"),
                self.document,
                **valid,
            )

        insecure_store = self.root / "insecure" / ".kittyecho-asr-artifacts"
        insecure_store.mkdir(parents=True)
        insecure_store.chmod(0o755)
        with self.assertRaisesRegex(ContractError, "0700"):
            commit_json_artifact(
                self.root / "insecure" / "receipt.json",
                self.document,
                **valid,
            )

        actual_store = self.root / "actual-store"
        actual_store.mkdir()
        symlink_parent = self.root / "symlink-parent"
        symlink_parent.symlink_to(actual_store, target_is_directory=True)
        with self.assertRaisesRegex(ContractError, "non-symlink directory"):
            commit_json_artifact(
                symlink_parent / "receipt.json",
                self.document,
                **valid,
            )

        parent_file = self.root / "not-a-directory"
        parent_file.write_text("occupied", encoding="utf-8")
        with self.assertRaisesRegex(ContractError, "directory"):
            commit_json_artifact(
                parent_file / "receipt.json",
                self.document,
                **valid,
            )

        symlink_store_parent = self.root / "symlink-store-parent"
        symlink_store_parent.mkdir()
        (symlink_store_parent / ".kittyecho-asr-artifacts").symlink_to(
            actual_store,
            target_is_directory=True,
        )
        with self.assertRaisesRegex(ContractError, "transaction store"):
            commit_json_artifact(
                symlink_store_parent / "receipt.json",
                self.document,
                **valid,
            )

    def test_consumer_boundary_and_expected_binding_attacks_fail_closed(
        self,
    ) -> None:
        from kittyecho_asr_bench.artifacts import (
            load_committed_json_artifact,
            validate_private_artifact_input_path,
            validate_private_artifact_output_path,
        )

        self._commit()
        for arguments, message in (
            ({"expected_kind": "other-report"}, "kind"),
            ({"expected_evidence_sha256": "c" * 64}, "evidence"),
            ({"expected_plan_sha256": "d" * 64}, "plan"),
        ):
            with self.subTest(arguments=arguments):
                with self.assertRaisesRegex(ContractError, message):
                    load_committed_json_artifact(
                        self.receipt,
                        **arguments,
                    )

        alias = self.root / "receipt-symlink.json"
        alias.symlink_to(self.receipt)
        normalized_alias = validate_private_artifact_input_path(
            alias,
            self.root / "repository",
        )
        self.assertEqual(normalized_alias.name, alias.name)
        self.assertNotEqual(normalized_alias, self.receipt.resolve())
        with self.assertRaisesRegex(ContractError, "non-symlink"):
            load_committed_json_artifact(normalized_alias)
        repository = self.root / "repository"
        repository.mkdir()
        with self.assertRaisesRegex(ContractError, "outside"):
            validate_private_artifact_output_path(
                repository / "report.json",
                repository,
            )

        shared = self.root / "receipt-hardlink.json"
        os.link(self.receipt, shared)
        with self.assertRaisesRegex(ContractError, "share"):
            load_committed_json_artifact(self.receipt)
        shared.unlink()

        for name, encoded, message in (
            ("malformed.json", b"{", "malformed"),
            ("duplicate.json", b'{"schema_version":"1.0","schema_version":"2.0"}', "duplicate"),
            ("array.json", b"[]", "JSON object"),
        ):
            path = self.root / name
            path.write_bytes(encoded)
            path.chmod(0o400)
            with self.subTest(name=name):
                with self.assertRaisesRegex(ContractError, message):
                    load_committed_json_artifact(path)

        self.receipt.chmod(0o600)
        with self.assertRaisesRegex(ContractError, "read-only"):
            load_committed_json_artifact(self.receipt)
        self.receipt.chmod(0o400)

        marker = json.loads(self.receipt.read_text(encoding="utf-8"))
        for index, (field, value, message) in enumerate(
            (
                ("schema_version", "2.0", "type or version"),
                ("artifact_kind", "../escape", "kind"),
                ("run_id", "run_bad", "run_id"),
                ("commit_sha256", "0" * 64, "commit hash"),
            )
        ):
            forged = dict(marker)
            forged[field] = value
            path = self.root / f"forged-receipt-{index}.json"
            path.write_text(
                json.dumps(forged, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            path.chmod(0o400)
            with self.subTest(field=field):
                with self.assertRaisesRegex(ContractError, message):
                    load_committed_json_artifact(path)

        nonfinite = self.root / "nonfinite.json"
        nonfinite.write_text('{"value":NaN}\n', encoding="utf-8")
        nonfinite.chmod(0o400)
        with self.assertRaisesRegex(ContractError, "non-finite"):
            load_committed_json_artifact(nonfinite)

        with self.assertRaisesRegex(ContractError, "regular file"):
            load_committed_json_artifact(self.root)


if __name__ == "__main__":
    unittest.main()
