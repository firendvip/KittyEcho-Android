from __future__ import annotations

import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPT_DIR))

import verify_paraformer_build_inputs as verifier


class VerifyParaformerBuildInputsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.repo_root = self.root / "repo"
        self.repo_root.mkdir()
        self.private = self.root / "private"
        self.private.mkdir()
        self.model_receipt = self.private / "model.int8.onnx.receipt.json"
        self.tokens_receipt = self.private / "tokens.txt.receipt.json"
        self.runtime_receipt = (
            self.private / "sherpa-onnx-1.13.3.aar.receipt.json"
        )
        self.runtime_aar = self.private / "sherpa-onnx-1.13.3.aar"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_frozen_constants_match_the_current_registry_receipts(self) -> None:
        self.assertEqual(
            "ac813fa0649bbd1a554603ae4c5a5301c48b0d5839611ea59ffe32b6373db860",
            verifier.REGISTRY_SNAPSHOT_SHA256,
        )
        self.assertEqual(
            "bc03d13ddb2184ab63ec3bf2a32ee8a856073ae54852774aaa68437dcc15e964",
            verifier.MODEL_RECEIPT_COMMIT_SHA256,
        )
        self.assertEqual(
            "c13885a083472c35d99fa2b97dbf98de5e46ef88bdf89daf0ea3c9ceaf79326e",
            verifier.TOKENS_RECEIPT_COMMIT_SHA256,
        )
        self.assertEqual(
            "18b8172f77a8bc4654804664b7a0fddfe5a42d44f36f21c12e9485e272257ba5",
            verifier.RUNTIME_RECEIPT_COMMIT_SHA256,
        )

    def test_accepts_only_stable_double_verified_canonical_inputs(self) -> None:
        model = evidence(
            "model.int8.onnx",
            self.private / "model.int8.onnx",
            223_385_835,
            verifier.MODEL_SHA256,
            verifier.MODEL_RECEIPT_COMMIT_SHA256,
        )
        tokens = evidence(
            "tokens.txt",
            self.private / "tokens.txt",
            75_756,
            verifier.TOKENS_SHA256,
            verifier.TOKENS_RECEIPT_COMMIT_SHA256,
        )
        runtime = evidence(
            "sherpa-onnx-1.13.3.aar",
            self.runtime_aar,
            57_044_841,
            verifier.RUNTIME_SHA256,
            verifier.RUNTIME_RECEIPT_COMMIT_SHA256,
        )
        with self.patched_gate(
            [model, tokens, runtime, model, tokens, runtime],
        ):
            result = self.verify()

        self.assertEqual("production_ready_verified_inputs", result["status"])
        self.assertEqual(verifier.RUNTIME_SHA256, result["runtime_sha256"])
        self.assertNotIn(str(self.private), str(result))

    def test_rejects_noncanonical_or_duplicate_receipt_names(self) -> None:
        for replacement in (
            self.private / "model.int8.onnx.freeze-receipt.json",
            self.tokens_receipt,
        ):
            with self.subTest(replacement=replacement.name):
                with self.assertRaises(verifier.ContractError):
                    verifier.verify_build_inputs(
                        repo_root=self.repo_root,
                        model_receipt=replacement,
                        tokens_receipt=self.tokens_receipt,
                        runtime_receipt=self.runtime_receipt,
                        runtime_aar=self.runtime_aar,
                    )

    def test_rejects_receipts_or_runtime_inside_repository(self) -> None:
        with self.assertRaises(verifier.ContractError):
            verifier.verify_build_inputs(
                repo_root=self.repo_root,
                model_receipt=self.repo_root / self.model_receipt.name,
                tokens_receipt=self.tokens_receipt,
                runtime_receipt=self.runtime_receipt,
                runtime_aar=self.runtime_aar,
            )
        with self.assertRaises(verifier.ContractError):
            verifier.verify_build_inputs(
                repo_root=self.repo_root,
                model_receipt=self.model_receipt,
                tokens_receipt=self.tokens_receipt,
                runtime_receipt=self.runtime_receipt,
                runtime_aar=self.repo_root / self.runtime_aar.name,
            )

    def test_rejects_wrong_artifact_identity_from_receipt_loader(self) -> None:
        wrong = evidence(
            "other.onnx",
            self.private / "model.int8.onnx",
            223_385_835,
            verifier.MODEL_SHA256,
            verifier.MODEL_RECEIPT_COMMIT_SHA256,
        )
        with self.patched_gate([wrong]):
            with self.assertRaises(verifier.ContractError):
                self.verify()

    def test_rejects_runtime_path_not_bound_by_runtime_receipt(self) -> None:
        inputs = canonical_evidence(self.private, self.runtime_aar)
        inputs[2] = evidence(
            "sherpa-onnx-1.13.3.aar",
            self.private / "different" / self.runtime_aar.name,
            57_044_841,
            verifier.RUNTIME_SHA256,
            verifier.RUNTIME_RECEIPT_COMMIT_SHA256,
        )
        with self.patched_gate(inputs):
            with self.assertRaises(verifier.ContractError):
                self.verify()

    def test_rejects_any_nonempty_gate_result(self) -> None:
        for blockers in (
            ["android_runtime_compatibility_compile_pending"],
            ["artifact_freeze_receipt_invalid"],
            [
                "android_runtime_compatibility_compile_pending",
                "artifact_freeze_receipt_required:tokens.txt",
            ],
        ):
            with self.subTest(blockers=blockers):
                inputs = canonical_evidence(self.private, self.runtime_aar)
                with self.patched_gate(inputs, blockers=blockers):
                    with self.assertRaises(verifier.ContractError):
                        self.verify()

    def test_rejects_receipt_or_artifact_change_between_gate_checks(self) -> None:
        first = canonical_evidence(self.private, self.runtime_aar)
        changed = canonical_evidence(self.private, self.runtime_aar)
        changed[1] = evidence(
            "tokens.txt",
            self.private / "replaced" / "tokens.txt",
            75_756,
            verifier.TOKENS_SHA256,
            verifier.TOKENS_RECEIPT_COMMIT_SHA256,
        )
        with self.patched_gate(first + changed):
            with self.assertRaises(verifier.ContractError):
                self.verify()

    def test_cli_forwards_all_explicit_paths_and_emits_only_gate_result(self) -> None:
        arguments = [
            "verify_paraformer_build_inputs.py",
            "--repo-root",
            str(self.repo_root),
            "--model-receipt",
            str(self.model_receipt),
            "--tokens-receipt",
            str(self.tokens_receipt),
            "--runtime-receipt",
            str(self.runtime_receipt),
            "--runtime-aar",
            str(self.runtime_aar),
        ]
        expected = {"status": "production_ready_verified_inputs"}
        with (
            mock.patch.object(sys, "argv", arguments),
            mock.patch.object(
                verifier,
                "verify_build_inputs",
                return_value=expected,
            ) as verify,
            mock.patch("builtins.print") as output,
        ):
            self.assertEqual(0, verifier.main())

        verify.assert_called_once_with(
            repo_root=self.repo_root,
            model_receipt=self.model_receipt,
            tokens_receipt=self.tokens_receipt,
            runtime_receipt=self.runtime_receipt,
            runtime_aar=self.runtime_aar,
        )
        output.assert_called_once_with(
            '{"status": "production_ready_verified_inputs"}',
        )

    def verify(self) -> dict[str, object]:
        return verifier.verify_build_inputs(
            repo_root=self.repo_root,
            model_receipt=self.model_receipt,
            tokens_receipt=self.tokens_receipt,
            runtime_receipt=self.runtime_receipt,
            runtime_aar=self.runtime_aar,
        )

    def patched_gate(
        self,
        load_results: list[SimpleNamespace],
        *,
        blockers: list[str] | None = None,
    ):
        stack = ExitStack()
        stack.enter_context(
            mock.patch.object(
                verifier,
                "load_download_freeze_receipt",
                side_effect=load_results,
            ),
        )
        stack.enter_context(
            mock.patch.object(
                verifier,
                "load_trusted_model_registry",
                return_value=SimpleNamespace(document={"trusted": True}),
            ),
        )
        stack.enter_context(
            mock.patch.object(
                verifier,
                "formal_benchmark_artifact_blockers",
                return_value=blockers
                if blockers is not None
                else [],
            ),
        )
        return stack


def canonical_evidence(
    private: Path,
    runtime_aar: Path,
) -> list[SimpleNamespace]:
    return [
        evidence(
            "model.int8.onnx",
            private / "model.int8.onnx",
            223_385_835,
            verifier.MODEL_SHA256,
            verifier.MODEL_RECEIPT_COMMIT_SHA256,
        ),
        evidence(
            "tokens.txt",
            private / "tokens.txt",
            75_756,
            verifier.TOKENS_SHA256,
            verifier.TOKENS_RECEIPT_COMMIT_SHA256,
        ),
        evidence(
            "sherpa-onnx-1.13.3.aar",
            runtime_aar,
            57_044_841,
            verifier.RUNTIME_SHA256,
            verifier.RUNTIME_RECEIPT_COMMIT_SHA256,
        ),
    ]


def evidence(
    filename: str,
    path: Path,
    size_bytes: int,
    sha256: str,
    receipt_sha256: str,
) -> SimpleNamespace:
    return SimpleNamespace(
        model_id="paraformer_int8",
        artifact_filename=filename,
        measured_bytes=size_bytes,
        measured_sha256=sha256,
        receipt_commit_sha256=receipt_sha256,
        registry_sha256=verifier.REGISTRY_SNAPSHOT_SHA256,
        measurement=SimpleNamespace(path=path),
    )


if __name__ == "__main__":
    unittest.main()
