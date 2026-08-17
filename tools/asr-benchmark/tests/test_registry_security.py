from __future__ import annotations

import copy
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import kittyecho_asr_bench.cli as benchmark_cli
import kittyecho_asr_bench.model_security as model_security
from kittyecho_asr_bench.artifacts import commit_json_artifact
from kittyecho_asr_bench.contracts import (
    ContractError,
    build_dataset_protocol,
    canonical_sha256,
    formal_benchmark_artifact_blockers,
    internal_evaluation_download_blockers,
)

from support import DEVICE_PROFILE_SHA256, write_json


class TrustedRegistryGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        self.private_root.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_generic_receipt_api_cannot_mint_model_security_kinds(self) -> None:
        document = {
            "schema_version": "1.0",
            "run_id": "run_0123456789ab",
        }
        for kind in (
            "model-internal-risk-acceptance",
            "model-artifact-download-freeze",
            "formal-model-cohort-closure",
        ):
            with self.subTest(kind=kind):
                with self.assertRaisesRegex(ContractError, "reserved"):
                    commit_json_artifact(
                        self.private_root / f"{kind}.json",
                        document,
                        artifact_kind=kind,
                        run_id=document["run_id"],
                        evidence_sha256="a" * 64,
                        plan_sha256="b" * 64,
                        validate_evidence=lambda: None,
                    )

    def test_caller_policy_flip_cannot_self_authorize_fire_or_nano(self) -> None:
        trusted = model_security.load_trusted_model_registry()
        for model_id in (
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ):
            forged = copy.deepcopy(trusted.document)
            model = next(
                item for item in forged["models"] if item["model_id"] == model_id
            )
            policy = model["evaluation_policy"]["internal_evaluation_download"]
            policy["clearance"] = "cleared"
            policy["user_risk_acceptance"] = "not_required"

            blockers = internal_evaluation_download_blockers(
                forged,
                model_id,
            )

            self.assertIn("untrusted_registry_snapshot", blockers)
            self.assertIn("user_risk_acceptance_receipt_required", blockers)

    def test_model_gate_custom_registry_is_development_only_and_never_go(
        self,
    ) -> None:
        trusted = model_security.load_trusted_model_registry()
        forged = copy.deepcopy(trusted.document)
        model = next(
            item
            for item in forged["models"]
            if item["model_id"] == "fireredasr2_aed_int8"
        )
        policy = model["evaluation_policy"]["internal_evaluation_download"]
        policy["clearance"] = "cleared"
        policy["user_risk_acceptance"] = "not_required"
        custom_path = self.private_root / "custom-registry.json"
        write_json(custom_path, forged)

        arguments = benchmark_cli._parser().parse_args(
            [
                "model-gate",
                "--registry",
                str(custom_path),
                "--model-id",
                "fireredasr2_aed_int8",
                "--purpose",
                "internal_evaluation_download",
            ]
        )
        result = json.loads(benchmark_cli._execute(arguments))

        self.assertEqual(result["status"], "NO_GO")
        self.assertIn("untrusted_registry_snapshot", result["blockers"])
        self.assertIn(
            "user_risk_acceptance_receipt_required",
            result["blockers"],
        )

    def test_model_gate_rejects_byte_identical_registry_at_custom_path(
        self,
    ) -> None:
        trusted = model_security.load_trusted_model_registry()
        copied_path = self.private_root / "copied-registry.json"
        copied_path.write_bytes(trusted.path.read_bytes())
        arguments = benchmark_cli._parser().parse_args(
            [
                "model-gate",
                "--registry",
                str(copied_path),
                "--model-id",
                "paraformer_int8",
                "--purpose",
                "internal_evaluation_download",
            ]
        )

        result = json.loads(benchmark_cli._execute(arguments))

        self.assertEqual(result["status"], "NO_GO")
        self.assertIn("untrusted_registry_snapshot", result["blockers"])

    def test_default_registry_tamper_fails_closed(self) -> None:
        original = model_security.load_trusted_model_registry()
        tampered = copy.deepcopy(original.document)
        tampered["models"][0]["display_name"] = "caller changed registry"
        tampered_path = self.private_root / "tampered-default.json"
        write_json(tampered_path, tampered)

        with mock.patch.object(
            model_security,
            "_TRUSTED_REGISTRY_PATH",
            tampered_path,
        ):
            with self.assertRaisesRegex(ContractError, "trusted registry"):
                model_security.load_trusted_model_registry()

    def test_no_acceptance_receipt_keeps_risk_models_blocked(self) -> None:
        trusted = model_security.load_trusted_model_registry()
        for model_id in (
            "fireredasr2_aed_int8",
            "funasr_nano_onnx_int8",
        ):
            blockers = internal_evaluation_download_blockers(
                trusted.document,
                model_id,
            )
            self.assertIn(
                "user_risk_acceptance_receipt_required",
                blockers,
            )

    def test_registry_reader_rejects_ambiguous_or_unsafe_inputs(self) -> None:
        for encoded, message in (
            (b'{"a":1,"a":2}', "duplicate"),
            (b'{"a":NaN}', "non-finite"),
            (b"[1,2,3]", "JSON object"),
            (b"\xff", "malformed JSON"),
        ):
            with self.subTest(encoded=encoded):
                with self.assertRaisesRegex(ContractError, message):
                    model_security._decode_json_object(
                        encoded,
                        context="test registry",
                    )

        self.assertTrue(
            model_security.registry_path_is_trusted(
                model_security.load_trusted_model_registry().path
            )
        )
        self.assertFalse(
            model_security.registry_path_is_trusted(Path("relative.json"))
        )
        self.assertFalse(
            model_security.registry_path_is_trusted(
                self.private_root / "missing.json"
            )
        )

        with self.assertRaisesRegex(ContractError, "absolute"):
            model_security._measure_regular_file(
                Path("relative.bin"),
                context="test artifact",
            )
        directory = self.private_root / "directory"
        directory.mkdir()
        with self.assertRaisesRegex(ContractError, "regular file"):
            model_security._measure_regular_file(
                directory,
                context="test artifact",
            )
        bounded = self.private_root / "bounded.bin"
        bounded.write_bytes(b"too-large")
        with self.assertRaisesRegex(ContractError, "bounded size"):
            model_security._measure_regular_file(
                bounded,
                context="test artifact",
                max_bytes=1,
            )


class DownloadFreezeReceiptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        self.private_root.mkdir()
        self.registry = model_security.load_trusted_model_registry()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_unknown_digest_is_frozen_from_safe_fd_and_bound_to_registry(
        self,
    ) -> None:
        artifact = self.private_root / "tokens.txt"
        artifact.write_bytes(b"x" * 79_172)
        receipt_path = self.private_root / "tokens-freeze-receipt.json"

        model_security.freeze_model_artifact(
            model_id="fireredasr2_aed_int8",
            artifact_filename="tokens.txt",
            artifact_path=artifact,
            receipt_path=receipt_path,
            repo_root=self.repo_root,
        )
        frozen = model_security.load_download_freeze_receipt(
            receipt_path,
            model_id="fireredasr2_aed_int8",
            artifact_filename="tokens.txt",
            repo_root=self.repo_root,
        )

        self.assertEqual(frozen.registry_sha256, self.registry.sha256)
        self.assertEqual(frozen.measured_bytes, 79_172)
        self.assertEqual(
            frozen.measured_sha256,
            hashlib.sha256(b"x" * 79_172).hexdigest(),
        )

    def test_caller_filled_digest_and_cross_revision_receipt_do_not_unblock(
        self,
    ) -> None:
        forged_registry = copy.deepcopy(self.registry.document)
        paraformer = next(
            model
            for model in forged_registry["models"]
            if model["model_id"] == "paraformer_int8"
        )
        token = next(
            artifact
            for artifact in paraformer["artifacts"]
            if artifact["filename"] == "tokens.txt"
        )
        token["sha256"] = "f" * 64
        token["verification_state"] = "locally_verified"

        blockers = formal_benchmark_artifact_blockers(
            forged_registry,
            "paraformer_int8",
        )

        self.assertIn("untrusted_registry_snapshot", blockers)
        self.assertIn(
            "artifact_freeze_receipt_required:tokens.txt",
            blockers,
        )

    def test_formal_blocker_requires_every_exact_safe_fd_receipt(self) -> None:
        artifact = self.private_root / "tokens.txt"
        artifact.write_bytes(b"y" * 79_172)
        receipt_path = self.private_root / "tokens-freeze-receipt.json"
        model_security.freeze_model_artifact(
            model_id="fireredasr2_aed_int8",
            artifact_filename="tokens.txt",
            artifact_path=artifact,
            receipt_path=receipt_path,
            repo_root=self.repo_root,
        )

        blockers = formal_benchmark_artifact_blockers(
            self.registry.document,
            "fireredasr2_aed_int8",
            artifact_freeze_receipt_paths=[receipt_path],
            repo_root=self.repo_root,
        )

        self.assertNotIn(
            "artifact_freeze_receipt_required:tokens.txt",
            blockers,
        )
        self.assertIn(
            "artifact_freeze_receipt_required:encoder.int8.onnx",
            blockers,
        )
        self.assertIn(
            "artifact_freeze_receipt_required:sherpa-onnx-1.13.3.aar",
            blockers,
        )
        self.assertIn("android_runtime_compatibility_compile_pending", blockers)

    def test_receipt_rejects_artifact_replacement_and_cross_source(self) -> None:
        artifact = self.private_root / "tokens.txt"
        artifact.write_bytes(b"z" * 79_172)
        receipt_path = self.private_root / "tokens-freeze-receipt.json"
        model_security.freeze_model_artifact(
            model_id="fireredasr2_aed_int8",
            artifact_filename="tokens.txt",
            artifact_path=artifact,
            receipt_path=receipt_path,
            repo_root=self.repo_root,
        )
        replacement = self.private_root / "replacement.txt"
        replacement.write_bytes(b"z" * 79_172)
        replacement.replace(artifact)

        with self.assertRaisesRegex(ContractError, "identity|inode|changed"):
            model_security.load_download_freeze_receipt(
                receipt_path,
                model_id="fireredasr2_aed_int8",
                artifact_filename="tokens.txt",
                repo_root=self.repo_root,
            )

    def test_freeze_rejects_symlink_shared_inode_and_wrong_known_size(
        self,
    ) -> None:
        target = self.private_root / "target.txt"
        target.write_bytes(b"s" * 75_756)
        symlink = self.private_root / "symlink.txt"
        symlink.symlink_to(target)
        with self.assertRaisesRegex(ContractError, "symlink|regular file"):
            model_security.freeze_model_artifact(
                model_id="paraformer_int8",
                artifact_filename="tokens.txt",
                artifact_path=symlink,
                receipt_path=self.private_root / "symlink-receipt.json",
                repo_root=self.repo_root,
            )

        hardlink = self.private_root / "hardlink.txt"
        os.link(target, hardlink)
        with self.assertRaisesRegex(ContractError, "share its inode"):
            model_security.freeze_model_artifact(
                model_id="paraformer_int8",
                artifact_filename="tokens.txt",
                artifact_path=target,
                receipt_path=self.private_root / "hardlink-receipt.json",
                repo_root=self.repo_root,
            )

        wrong_size = self.private_root / "model.int8.onnx"
        wrong_size.write_bytes(b"not-the-publisher-artifact")
        with self.assertRaisesRegex(ContractError, "byte count"):
            model_security.freeze_model_artifact(
                model_id="paraformer_int8",
                artifact_filename="model.int8.onnx",
                artifact_path=wrong_size,
                receipt_path=self.private_root / "wrong-size-receipt.json",
                repo_root=self.repo_root,
            )

    def test_freeze_receipt_is_not_reusable_for_another_model_or_artifact(
        self,
    ) -> None:
        artifact = self.private_root / "tokens.txt"
        artifact.write_bytes(b"r" * 79_172)
        receipt_path = self.private_root / "tokens-receipt.json"
        model_security.freeze_model_artifact(
            model_id="fireredasr2_aed_int8",
            artifact_filename="tokens.txt",
            artifact_path=artifact,
            receipt_path=receipt_path,
            repo_root=self.repo_root,
        )
        for model_id, filename in (
            ("paraformer_int8", "tokens.txt"),
            ("fireredasr2_aed_int8", "encoder.int8.onnx"),
        ):
            with self.subTest(model_id=model_id, filename=filename):
                with self.assertRaisesRegex(
                    ContractError,
                    "another model|another artifact",
                ):
                    model_security.load_download_freeze_receipt(
                        receipt_path,
                        model_id=model_id,
                        artifact_filename=filename,
                        repo_root=self.repo_root,
                    )

    def test_freeze_cli_uses_only_trusted_registry_and_safe_fd(self) -> None:
        artifact = self.private_root / "tokens-cli.txt"
        artifact.write_bytes(b"q" * 79_172)
        receipt_path = self.private_root / "tokens-cli-receipt.json"
        arguments = benchmark_cli._parser().parse_args(
            [
                "freeze-model-artifact",
                "--model-id",
                "fireredasr2_aed_int8",
                "--artifact-filename",
                "tokens.txt",
                "--artifact",
                str(artifact),
                "--receipt",
                str(receipt_path),
                "--repo-root",
                str(self.repo_root),
            ]
        )

        message = benchmark_cli._execute(arguments)
        frozen = model_security.load_download_freeze_receipt(
            receipt_path,
            model_id="fireredasr2_aed_int8",
            artifact_filename="tokens.txt",
            repo_root=self.repo_root,
        )

        self.assertIn("frozen fireredasr2_aed_int8/tokens.txt", message)
        self.assertEqual(frozen.measured_bytes, 79_172)

    def test_freeze_measurement_document_rejects_malformed_identity(self) -> None:
        valid = {
            "path": str(self.private_root / "artifact.bin"),
            "sha256": "a" * 64,
            "st_dev": 1,
            "st_ino": 2,
            "size_bytes": 3,
            "mode": 0o400,
            "nlink": 1,
        }
        for attacked, message in (
            (None, "object"),
            ({**valid, "extra": True}, "fields"),
            ({**valid, "path": "relative.bin"}, "absolute"),
            ({**valid, "sha256": "A" * 64}, "lowercase"),
            ({**valid, "st_dev": True}, "non-negative"),
            ({**valid, "size_bytes": -1}, "non-negative"),
            ({**valid, "nlink": 2}, "one inode"),
        ):
            with self.subTest(message=message):
                with self.assertRaisesRegex(ContractError, message):
                    model_security._measurement_from_document(attacked)


class ReservedModelReceiptContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        self.private_root.mkdir()
        self.trusted = model_security.load_trusted_model_registry()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_risk_acceptance_contract_binds_exact_internal_scope_and_revision(
        self,
    ) -> None:
        model = next(
            item
            for item in self.trusted.document["models"]
            if item["model_id"] == "fireredasr2_aed_int8"
        )
        source = model["official_source"]
        binding = {
            "contract_id": "model-internal-risk-acceptance-v1",
            "scope": "internal_evaluation_download_only",
            "approval_status": "approved",
            "registry_snapshot_sha256": self.trusted.sha256,
            "model_id": model["model_id"],
            "model_revision": source["revision"],
            "model_revision_kind": source["revision_kind"],
            "restrictions": [
                "repository_external_only",
                "private_device_only",
                "no_redistribution",
                "no_upload",
            ],
            "authority_decision_commitment_sha256": "a" * 64,
        }
        document = {
            "schema_version": "1.0",
            "run_id": "run_" + canonical_sha256(binding)[:12],
            "receipt_contract_id": binding["contract_id"],
            **{key: value for key, value in binding.items() if key != "contract_id"},
        }
        self.assertEqual(
            model_security._validate_risk_acceptance_document(
                document,
                trusted=self.trusted,
                model_id=model["model_id"],
            ),
            canonical_sha256(binding),
        )

        for field, replacement in (
            ("scope", "production_distribution"),
            ("approval_status", "pending"),
            ("registry_snapshot_sha256", "b" * 64),
            ("model_revision", "other/release.bin"),
            ("authority_decision_commitment_sha256", "not-a-hash"),
        ):
            attacked = copy.deepcopy(document)
            attacked[field] = replacement
            with self.subTest(field=field):
                with self.assertRaises(ContractError):
                    model_security._validate_risk_acceptance_document(
                        attacked,
                        trusted=self.trusted,
                        model_id=model["model_id"],
                    )

    def test_risk_acceptance_loader_revalidates_reserved_transport(self) -> None:
        model = next(
            item
            for item in self.trusted.document["models"]
            if item["model_id"] == "fireredasr2_aed_int8"
        )
        source = model["official_source"]
        binding = {
            "contract_id": "model-internal-risk-acceptance-v1",
            "scope": "internal_evaluation_download_only",
            "approval_status": "approved",
            "registry_snapshot_sha256": self.trusted.sha256,
            "model_id": model["model_id"],
            "model_revision": source["revision"],
            "model_revision_kind": source["revision_kind"],
            "restrictions": [
                "repository_external_only",
                "private_device_only",
                "no_redistribution",
                "no_upload",
            ],
            "authority_decision_commitment_sha256": "a" * 64,
        }
        document = {
            "schema_version": "1.0",
            "run_id": "run_" + canonical_sha256(binding)[:12],
            "receipt_contract_id": binding["contract_id"],
            **{
                key: value
                for key, value in binding.items()
                if key != "contract_id"
            },
        }
        transport_receipt = {
            "plan_sha256": canonical_sha256(binding),
            "commit_sha256": "b" * 64,
        }
        receipt_path = self.private_root / "external-risk-receipt.json"

        with mock.patch.object(
            model_security,
            "load_committed_json_artifact",
            return_value=(document, transport_receipt),
        ):
            loaded = model_security.load_internal_risk_acceptance_receipt(
                receipt_path,
                model_id=model["model_id"],
                repo_root=self.repo_root,
            )
            self.assertEqual(loaded["commit_sha256"], "b" * 64)

            wrong_plan = dict(transport_receipt)
            wrong_plan["plan_sha256"] = "c" * 64
            with mock.patch.object(
                model_security,
                "load_committed_json_artifact",
                return_value=(document, wrong_plan),
            ):
                with self.assertRaisesRegex(ContractError, "plan binding"):
                    model_security.load_internal_risk_acceptance_receipt(
                        receipt_path,
                        model_id=model["model_id"],
                        repo_root=self.repo_root,
                    )

    def _synthetic_formal_receipts(
        self,
    ) -> tuple[
        tuple[str, ...],
        list[Path],
        dict[str, Path],
        dict[Path, model_security.DownloadFreezeEvidence],
    ]:
        cohort = self.trusted.document["formal_cohorts"][0]
        model_ids = tuple(cohort["model_ids"])
        artifact_paths: list[Path] = []
        risk_paths: dict[str, Path] = {}
        evidence_by_path: dict[
            Path,
            model_security.DownloadFreezeEvidence,
        ] = {}
        inode = 100
        for model in self.trusted.document["models"]:
            if model["model_id"] not in model_ids:
                continue
            source = model["official_source"]
            for artifact in model["artifacts"]:
                receipt_path = (
                    self.private_root
                    / f"{model['model_id']}-{artifact['filename']}.receipt"
                )
                frozen_path = (
                    self.private_root
                    / f"{model['model_id']}-{artifact['filename']}"
                )
                digest = artifact["sha256"] or hashlib.sha256(
                    f"{model['model_id']}:{artifact['filename']}".encode()
                ).hexdigest()
                size = artifact["expected_bytes"] or 1
                measurement = model_security._FileMeasurement(
                    path=frozen_path,
                    st_dev=1,
                    st_ino=inode,
                    size_bytes=size,
                    mode=0o400,
                    nlink=1,
                    sha256=digest,
                )
                inode += 1
                evidence_by_path[receipt_path] = (
                    model_security.DownloadFreezeEvidence(
                        receipt_path=receipt_path,
                        receipt_commit_sha256=hashlib.sha256(
                            str(receipt_path).encode()
                        ).hexdigest(),
                        registry_sha256=self.trusted.sha256,
                        model_id=model["model_id"],
                        model_revision=source["revision"],
                        model_revision_kind=source["revision_kind"],
                        artifact_filename=artifact["filename"],
                        artifact_component=artifact["component"],
                        artifact_source_url=artifact["source_url"],
                        artifact_source_revision=artifact["source_revision"],
                        artifact_source_revision_kind=(
                            artifact["source_revision_kind"]
                        ),
                        measured_bytes=size,
                        measured_sha256=digest,
                        measurement=measurement,
                    )
                )
                artifact_paths.append(receipt_path)
            if (
                model["evaluation_policy"]["internal_evaluation_download"][
                    "clearance"
                ]
                == "requires_user_risk_acceptance"
            ):
                risk_paths[model["model_id"]] = (
                    self.private_root
                    / f"{model['model_id']}-external-risk.receipt"
                )
        return model_ids, artifact_paths, risk_paths, evidence_by_path

    def test_verified_formal_cohort_derives_every_frozen_commitment(self) -> None:
        model_ids, artifact_paths, risk_paths, evidence_by_path = (
            self._synthetic_formal_receipts()
        )

        def load_freeze(
            path: Path,
            **_kwargs: object,
        ) -> model_security.DownloadFreezeEvidence:
            return evidence_by_path[Path(path)]

        def load_risk(
            path: Path,
            *,
            model_id: str,
            **_kwargs: object,
        ) -> dict[str, str]:
            self.assertEqual(Path(path), risk_paths[model_id])
            return {
                "commit_sha256": hashlib.sha256(model_id.encode()).hexdigest()
            }

        with (
            mock.patch.object(
                model_security,
                "load_download_freeze_receipt",
                side_effect=load_freeze,
            ),
            mock.patch.object(
                model_security,
                "load_internal_risk_acceptance_receipt",
                side_effect=load_risk,
            ),
            mock.patch(
                "kittyecho_asr_bench.contracts."
                "formal_benchmark_artifact_blockers",
                return_value=[],
            ),
        ):
            evidence = model_security.verify_formal_model_cohort(
                profile="exploration",
                model_ids=model_ids,
                artifact_freeze_receipt_paths=artifact_paths,
                risk_acceptance_receipt_paths=risk_paths,
                repo_root=self.repo_root,
            )
            commitments = model_security.formal_model_cohort_commitments(
                evidence
            )

        self.assertEqual(
            set(commitments),
            {
                "formal_artifact_set_sha256",
                "formal_risk_acceptance_set_sha256",
                "formal_model_cohort_sha256",
            },
        )
        self.assertTrue(all(len(value) == 64 for value in commitments.values()))
        with self.assertRaisesRegex(ContractError, "immutable"):
            evidence.caller_supplied = True

    def test_formal_cohort_rejects_partial_duplicate_and_cross_model_inputs(
        self,
    ) -> None:
        model_ids, artifact_paths, _risk_paths, evidence_by_path = (
            self._synthetic_formal_receipts()
        )

        with self.assertRaisesRegex(ContractError, "exactly equal"):
            model_security.verify_formal_model_cohort(
                profile="exploration",
                model_ids=model_ids[:-1],
                artifact_freeze_receipt_paths=(),
                risk_acceptance_receipt_paths={},
                repo_root=self.repo_root,
            )
        with self.assertRaisesRegex(ContractError, "profile"):
            model_security.verify_formal_model_cohort(
                profile="development_fixture",
                model_ids=model_ids,
                artifact_freeze_receipt_paths=(),
                risk_acceptance_receipt_paths={},
                repo_root=self.repo_root,
            )

        duplicate = [artifact_paths[0], artifact_paths[0]]
        with mock.patch.object(
            model_security,
            "load_download_freeze_receipt",
            side_effect=lambda path, **_kwargs: evidence_by_path[Path(path)],
        ):
            with self.assertRaisesRegex(ContractError, "duplicate"):
                model_security.verify_formal_model_cohort(
                    profile="exploration",
                    model_ids=model_ids,
                    artifact_freeze_receipt_paths=duplicate,
                    risk_acceptance_receipt_paths={},
                    repo_root=self.repo_root,
                )

        outside_path = self.private_root / "outside.receipt"
        outside = next(iter(evidence_by_path.values()))
        outside_measurement = model_security._FileMeasurement(
            path=self.private_root / "outside.bin",
            st_dev=1,
            st_ino=999,
            size_bytes=1,
            mode=0o400,
            nlink=1,
            sha256="d" * 64,
        )
        outside_evidence = model_security.DownloadFreezeEvidence(
            receipt_path=outside_path,
            receipt_commit_sha256="e" * 64,
            registry_sha256=self.trusted.sha256,
            model_id="qwen3_asr_0_6b_second_batch",
            model_revision=outside.model_revision,
            model_revision_kind=outside.model_revision_kind,
            artifact_filename="outside.bin",
            artifact_component="weights",
            artifact_source_url="https://official.invalid/outside.bin",
            artifact_source_revision=outside.model_revision,
            artifact_source_revision_kind=outside.model_revision_kind,
            measured_bytes=1,
            measured_sha256="d" * 64,
            measurement=outside_measurement,
        )
        with mock.patch.object(
            model_security,
            "load_download_freeze_receipt",
            return_value=outside_evidence,
        ):
            with self.assertRaisesRegex(ContractError, "outside the cohort"):
                model_security.verify_formal_model_cohort(
                    profile="exploration",
                    model_ids=model_ids,
                    artifact_freeze_receipt_paths=[outside_path],
                    risk_acceptance_receipt_paths={},
                    repo_root=self.repo_root,
                )

        with mock.patch.object(
            model_security,
            "load_download_freeze_receipt",
            side_effect=lambda path, **_kwargs: evidence_by_path[Path(path)],
        ):
            with self.assertRaisesRegex(ContractError, "risk receipt outside"):
                model_security.verify_formal_model_cohort(
                    profile="exploration",
                    model_ids=model_ids,
                    artifact_freeze_receipt_paths=artifact_paths,
                    risk_acceptance_receipt_paths={
                        "qwen3_asr_0_6b_second_batch": (
                            self.private_root / "outside-risk.receipt"
                        )
                    },
                    repo_root=self.repo_root,
                )

    def test_formal_cohort_fails_closed_when_any_model_gate_blocks(self) -> None:
        model_ids, artifact_paths, risk_paths, evidence_by_path = (
            self._synthetic_formal_receipts()
        )
        with (
            mock.patch.object(
                model_security,
                "load_download_freeze_receipt",
                side_effect=lambda path, **_kwargs: evidence_by_path[
                    Path(path)
                ],
            ),
            mock.patch.object(
                model_security,
                "load_internal_risk_acceptance_receipt",
                return_value={"commit_sha256": "f" * 64},
            ),
            mock.patch(
                "kittyecho_asr_bench.contracts."
                "formal_benchmark_artifact_blockers",
                return_value=["android_runtime_compatibility_compile_pending"],
            ),
        ):
            with self.assertRaisesRegex(
                ContractError,
                "formal benchmark artifact gate blocked",
            ):
                model_security.verify_formal_model_cohort(
                    profile="production_confirmation",
                    model_ids=model_ids,
                    artifact_freeze_receipt_paths=artifact_paths,
                    risk_acceptance_receipt_paths=risk_paths,
                    repo_root=self.repo_root,
                )

    def test_formal_closure_is_reserved_and_bound_to_run_plan_and_evidence(
        self,
    ) -> None:
        cohort = self.trusted.document["formal_cohorts"][0]
        record = model_security._FormalModelCohortRecord(
            profile="exploration",
            cohort_id=cohort["cohort_id"],
            model_ids=tuple(cohort["model_ids"]),
            baseline_model_id=cohort["baseline_model_id"],
            registry_sha256=self.trusted.sha256,
            repo_root=self.repo_root.resolve(),
            artifact_receipt_paths=(),
            risk_acceptance_receipt_paths=(),
            artifact_set_sha256="7" * 64,
            risk_acceptance_set_sha256="8" * 64,
            cohort_commitment_sha256="9" * 64,
        )
        evidence = object.__new__(model_security.VerifiedFormalModelCohort)
        plan = {
            "run_id": "run_0123456789ab",
            "protocol_profile": "exploration",
            "cohort_id": cohort["cohort_id"],
            "cohort_model_ids_sha256": canonical_sha256(
                sorted(cohort["model_ids"])
            ),
            "assignments": [
                {"model_alias": f"M{index:03d}"}
                for index in range(1, len(cohort["model_ids"]) + 1)
            ],
            "contract_fingerprints": {
                "registry_sha256": self.trusted.sha256,
            },
            "formal_artifact_set_sha256": record.artifact_set_sha256,
            "formal_risk_acceptance_set_sha256": (
                record.risk_acceptance_set_sha256
            ),
            "formal_model_cohort_sha256": (
                record.cohort_commitment_sha256
            ),
        }
        receipt_path = self.private_root / "formal-closure.json"
        evidence_sha256 = "6" * 64
        with mock.patch.object(
            model_security,
            "_revalidate_formal_model_cohort",
            return_value=record,
        ):
            model_security.commit_formal_model_cohort_closure(
                evidence,
                public_plan=plan,
                benchmark_evidence_sha256=evidence_sha256,
                receipt_path=receipt_path,
                repo_root=self.repo_root,
            )

        loaded = model_security.load_formal_model_cohort_closure(
            receipt_path,
            public_plan=plan,
            benchmark_evidence_sha256=evidence_sha256,
            repo_root=self.repo_root,
        )
        self.assertEqual(loaded["run_id"], plan["run_id"])

        changed_run = copy.deepcopy(plan)
        changed_run["run_id"] = "run_ffffffffffff"
        with self.assertRaisesRegex(ContractError, "fingerprint|run|plan"):
            model_security.load_formal_model_cohort_closure(
                receipt_path,
                public_plan=changed_run,
                benchmark_evidence_sha256=evidence_sha256,
                repo_root=self.repo_root,
            )
        with self.assertRaisesRegex(ContractError, "evidence"):
            model_security.load_formal_model_cohort_closure(
                receipt_path,
                public_plan=plan,
                benchmark_evidence_sha256="5" * 64,
                repo_root=self.repo_root,
            )

    def test_formal_cohort_capability_cannot_be_caller_constructed(self) -> None:
        with self.assertRaisesRegex(ContractError, "trusted receipt verifier"):
            model_security.VerifiedFormalModelCohort()


class FormalCliArtifactGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.private_root = self.root / "private"
        self.repo_root.mkdir()
        self.private_root.mkdir()
        self.protocol = build_dataset_protocol(
            "exploration",
            protocol_id="protocol_111111111111",
            target_device={
                "platform": "android",
                "physical_device": True,
                "abi": "arm64-v8a",
                "device_profile_sha256": DEVICE_PROFILE_SHA256,
            },
            approval_status="approved",
        )
        self.protocol_path = self.private_root / "protocol.json"
        write_json(self.protocol_path, self.protocol)
        self.models = (
            "zipformer_baseline,paraformer_int8,"
            "fireredasr2_aed_int8,funasr_nano_onnx_int8"
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _blind_arguments(self, registry_path: Path) -> list[str]:
        return [
            "blind",
            "--manifest",
            str(self.private_root / "missing-manifest.json"),
            "--repo-root",
            str(self.repo_root),
            "--snapshot-root",
            str(self.private_root / "snapshots"),
            "--dataset-protocol",
            str(self.protocol_path),
            "--registry",
            str(registry_path),
            "--models",
            self.models,
            "--seed",
            str((1 << 127) + 99),
            "--public-plan",
            str(self.private_root / "public-plan.json"),
            "--private-map",
            str(self.private_root / "private-map.json"),
            "--formal-cohort-receipt",
            str(self.private_root / "cohort-receipt.json"),
        ]

    def _formal_public_plan_receipt(self) -> Path:
        plan = {
            "schema_version": "1.0",
            "run_id": "run_0123456789ab",
            "dataset_id": "dataset_0123456789ab",
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "protocol_profile": "exploration",
            "cohort_id": "first_batch_android_v1",
            "cohort_model_ids_sha256": "a" * 64,
            "formal_artifact_set_sha256": None,
            "formal_risk_acceptance_set_sha256": None,
            "formal_model_cohort_sha256": None,
            "assignments": [
                {
                    "model_alias": "M001",
                    "clip_order": [
                        "clip_000000000000",
                        "clip_000000000001",
                    ],
                },
                {
                    "model_alias": "M002",
                    "clip_order": [
                        "clip_000000000001",
                        "clip_000000000000",
                    ],
                },
            ],
            "baseline_commitment_sha256": "b" * 64,
            "clip_pcm_sha256": {
                "clip_000000000000": "c" * 64,
                "clip_000000000001": "d" * 64,
            },
            "clip_pcm_payload_sha256": {
                "clip_000000000000": "e" * 64,
                "clip_000000000001": "f" * 64,
            },
            "randomization": {
                "algorithm": "sha256-fisher-yates-v1",
                "private_map_sha256": "1" * 64,
            },
            "contract_fingerprints": {
                "manifest_sha256": "2" * 64,
                "dataset_protocol_sha256": "3" * 64,
                "normalization_sha256": "4" * 64,
                "selection_config_sha256": "5" * 64,
                "registry_sha256": (
                    model_security.load_trusted_model_registry().sha256
                ),
            },
        }
        receipt_path = self.private_root / "formal-public-plan.json"
        commit_json_artifact(
            receipt_path,
            plan,
            artifact_kind="blind-public-plan",
            run_id=plan["run_id"],
            evidence_sha256="6" * 64,
            plan_sha256=canonical_sha256(plan),
            validate_evidence=lambda: None,
        )
        return receipt_path

    def test_formal_blind_rejects_custom_registry_before_dataset_access(
        self,
    ) -> None:
        custom = self.private_root / "custom-registry.json"
        write_json(
            custom,
            model_security.load_trusted_model_registry().document,
        )
        arguments = benchmark_cli._parser().parse_args(
            self._blind_arguments(custom)
        )
        with self.assertRaisesRegex(ContractError, "trusted registry"):
            benchmark_cli._execute(arguments)

    def test_formal_blind_calls_artifact_gate_before_dataset_access(
        self,
    ) -> None:
        trusted_path = model_security.load_trusted_model_registry().path
        arguments = benchmark_cli._parser().parse_args(
            self._blind_arguments(trusted_path)
        )
        with self.assertRaisesRegex(
            ContractError,
            "formal benchmark artifact gate blocked",
        ) as raised:
            benchmark_cli._execute(arguments)
        message = str(raised.exception)
        self.assertIn("artifact_freeze_receipt_required", message)
        self.assertIn("user_risk_acceptance_receipt_required", message)
        self.assertNotIn("missing-manifest", message)

    def test_formal_score_requires_cohort_receipt_before_private_inputs(
        self,
    ) -> None:
        arguments = benchmark_cli._parser().parse_args(
            [
                "score",
                "--manifest",
                str(self.private_root / "missing-manifest.json"),
                "--references",
                str(self.private_root / "missing-references.json"),
                "--public-plan",
                str(self.private_root / "missing-plan.json"),
                "--dataset-protocol",
                str(self.protocol_path),
                "--repo-root",
                str(self.repo_root),
                "--snapshot-root",
                str(self.private_root / "snapshots"),
                "--report",
                str(self.private_root / "report.json"),
            ]
        )
        with self.assertRaisesRegex(
            ContractError,
            "formal model cohort closure receipt required",
        ):
            benchmark_cli._execute(arguments)

    def test_formal_score_rechecks_artifact_gate_before_private_inputs(
        self,
    ) -> None:
        arguments = benchmark_cli._parser().parse_args(
            [
                "score",
                "--manifest",
                str(self.private_root / "missing-manifest.json"),
                "--references",
                str(self.private_root / "missing-references.json"),
                "--public-plan",
                str(self.private_root / "missing-plan.json"),
                "--dataset-protocol",
                str(self.protocol_path),
                "--repo-root",
                str(self.repo_root),
                "--snapshot-root",
                str(self.private_root / "snapshots"),
                "--report",
                str(self.private_root / "report.json"),
                "--formal-cohort-receipt",
                str(self.private_root / "cohort-receipt.json"),
            ]
        )
        with self.assertRaisesRegex(
            ContractError,
            "formal benchmark artifact gate blocked",
        ) as raised:
            benchmark_cli._execute(arguments)
        self.assertIn(
            "artifact_freeze_receipt_required",
            str(raised.exception),
        )
        self.assertNotIn("missing-manifest", str(raised.exception))

    def test_every_formal_downstream_cli_requires_cohort_receipt_first(
        self,
    ) -> None:
        public_plan = self._formal_public_plan_receipt()
        commands = (
            [
                "make-decoder-plan",
                "--manifest",
                str(self.private_root / "missing-manifest.json"),
                "--public-plan",
                str(public_plan),
                "--model-alias",
                "M001",
                "--repo-root",
                str(self.repo_root),
                "--snapshot-root",
                str(self.private_root / "snapshots"),
                "--plan",
                str(self.private_root / "decoder-plan.json"),
            ],
            [
                "sanitize-engineering",
                "--manifest",
                str(self.private_root / "missing-manifest.json"),
                "--accuracy-output",
                str(self.private_root / "missing-accuracy.json"),
                "--accuracy-report",
                str(self.private_root / "missing-report.json"),
                "--engineering-proof",
                str(self.private_root / "missing-proof.json"),
                "--public-plan",
                str(public_plan),
                "--dataset-protocol",
                str(self.protocol_path),
                "--repo-root",
                str(self.repo_root),
                "--snapshot-root",
                str(self.private_root / "snapshots"),
                "--report",
                str(self.private_root / "engineering-report.json"),
            ],
            [
                "select",
                "--reports",
                str(self.private_root / "missing-report-one.json"),
                str(self.private_root / "missing-report-two.json"),
                "--public-plan",
                str(public_plan),
                "--repo-root",
                str(self.repo_root),
                "--report",
                str(self.private_root / "selection.json"),
            ],
        )
        for command in commands:
            with self.subTest(command=command[0]):
                arguments = benchmark_cli._parser().parse_args(command)
                with self.assertRaisesRegex(
                    ContractError,
                    "formal model cohort closure receipt required",
                ):
                    benchmark_cli._execute(arguments)
