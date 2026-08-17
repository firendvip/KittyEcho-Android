from __future__ import annotations

import ast
import base64
import copy
import datetime
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID, ObjectIdentifier

from kittyecho_asr_bench.android_attestation import (
    AndroidAccuracyLoadPolicy,
    AndroidAttestationPolicy,
    _validate_accuracy,
    _validate_decoder_plan,
    _validate_runtime,
    _validate_telemetry,
    attestation_message_bytes,
    build_attestation_commitments,
    load_development_synthetic_android_accuracy,
    load_verified_android_accuracy,
    verify_and_commit_android_bundle,
)
from kittyecho_asr_bench.artifacts import (
    commit_json_artifact,
    load_committed_json_artifact,
)
from kittyecho_asr_bench.contracts import ContractError, canonical_sha256
from kittyecho_asr_bench.cli import main


SHA_A = "a" * 64
SHA_B = "b" * 64
SHA_C = "c" * 64
SHA_D = "d" * 64
SHA_E = "e" * 64
SHA_F = "f" * 64
RUN_ID = "run_0123456789ab"
MODEL_ALIAS = "M001"
CLIP_ID = "clip_0123456789ab"
DATASET_ID = "dataset_0123456789ab"
HOST_CHALLENGE = b"\x42" * 32
ANDROID_ATTESTATION_OID = ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")


def _der_length(length: int) -> bytes:
    if length < 128:
        return bytes([length])
    encoded = length.to_bytes((length.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(encoded)]) + encoded


def _der(tag: bytes, payload: bytes) -> bytes:
    return tag + _der_length(len(payload)) + payload


def _der_integer_payload(value: int) -> bytes:
    if value < 0:
        raise ValueError("test DER integers must be non-negative")
    encoded = value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big")
    return b"\x00" + encoded if encoded[0] & 0x80 else encoded


def _der_integer(value: int) -> bytes:
    return _der(b"\x02", _der_integer_payload(value))


def _der_enumerated(value: int) -> bytes:
    return _der(b"\x0a", _der_integer_payload(value))


def _der_octets(value: bytes) -> bytes:
    return _der(b"\x04", value)


def _der_sequence(*values: bytes) -> bytes:
    return _der(b"\x30", b"".join(values))


def _der_set(*values: bytes) -> bytes:
    return _der(b"\x31", b"".join(values))


def _der_context(tag_number: int, payload: bytes) -> bytes:
    groups = [tag_number & 0x7F]
    remaining = tag_number >> 7
    while remaining:
        groups.append(remaining & 0x7F)
        remaining >>= 7
    ordered = list(reversed(groups))
    encoded_tag = bytes(
        [0xBF]
        + [
            group | (0x80 if index < len(ordered) - 1 else 0)
            for index, group in enumerate(ordered)
        ]
    )
    return _der(encoded_tag, payload)


def _android_attestation_extension(
    *,
    challenge: bytes,
    package_name: str,
    version_code: int,
    signing_cert_sha256: str,
) -> bytes:
    application_id = _der_sequence(
        _der_set(
            _der_sequence(
                _der_octets(package_name.encode("utf-8")),
                _der_integer(version_code),
            )
        ),
        _der_set(_der_octets(bytes.fromhex(signing_cert_sha256))),
    )
    application_authorization = _der_context(
        709,
        _der_octets(application_id),
    )
    root_of_trust = _der_sequence(
        _der_octets(b"verified-boot-key"),
        _der(b"\x01", b"\xff"),
        _der_enumerated(0),
        _der_octets(b"verified-boot-hash"),
    )
    root_authorization = _der_context(704, root_of_trust)
    return _der_sequence(
        _der_integer(400),
        _der_enumerated(1),
        _der_integer(400),
        _der_enumerated(1),
        _der_octets(challenge),
        _der_octets(b""),
        _der_sequence(application_authorization),
        _der_sequence(root_authorization),
    )


class AndroidAttestationHostVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo_root = self.root / "repo"
        self.repo_root.mkdir()
        self.private_root = self.root / "private"
        self.private_root.mkdir()
        self.decoder_receipt_path = self.private_root / "decoder.receipt.json"
        self.accuracy_path = self.private_root / "accuracy.raw.json"
        self.engineering_path = self.private_root / "engineering.raw.json"
        self.envelope_path = self.private_root / "attestation.raw.json"
        self.accuracy_receipt_path = (
            self.private_root / "accuracy.verified.receipt.json"
        )
        self.engineering_receipt_path = (
            self.private_root / "engineering.verified.receipt.json"
        )
        self.attestation_receipt_path = (
            self.private_root / "attestation.verified.receipt.json"
        )

        self.decoder_plan = self._decoder_plan()
        self.decoder_receipt = commit_json_artifact(
            self.decoder_receipt_path,
            self.decoder_plan,
            artifact_kind="decoder-plan",
            run_id=RUN_ID,
            evidence_sha256=SHA_A,
            plan_sha256=SHA_B,
            validate_evidence=lambda: None,
        )
        self.accuracy = self._accuracy_output()
        self.engineering = self._engineering_output()
        self.private_key, certificate = self._development_certificate()
        self.certificate_der = certificate.public_bytes(
            serialization.Encoding.DER
        )
        self.certificate_chain_der = [self.certificate_der]
        self.envelope = self._signed_envelope()
        self._write_raw_inputs()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _decoder_plan(self) -> dict[str, object]:
        plan: dict[str, object] = {
            "schema_version": "1.0",
            "run_id": RUN_ID,
            "dataset_id": DATASET_ID,
            "model_alias": MODEL_ALIAS,
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "decoder_contract_id": "first-layer-raw-v1",
            "input_transform_id": "canonical-pcm-direct-v1",
            "public_plan_sha256": SHA_B,
            "manifest_sha256": SHA_C,
            "clips": [
                {
                    "clip_id": CLIP_ID,
                    "audio_path": "/data/user/0/dev.fixture/files/frozen.wav",
                    "wav_file_sha256": SHA_D,
                    "pcm_payload_sha256": SHA_E,
                    "pcm_payload_bytes": 320,
                }
            ],
        }
        plan["plan_id"] = f"plan_{canonical_sha256(plan)[:12]}"
        return plan

    def _accuracy_output(self) -> dict[str, object]:
        return {
            "schema_version": "1.0",
            "run_id": RUN_ID,
            "model_alias": MODEL_ALIAS,
            "decoder_contract_id": "first-layer-raw-v1",
            "pcm_contract_id": "pcm16k-mono-s16le-v1",
            "input_transform_id": "canonical-pcm-direct-v1",
            "decoder_plan_sha256": canonical_sha256(self.decoder_plan),
            "reference_accessed": False,
            "predictions": [
                {
                    "clip_id": CLIP_ID,
                    "pcm_sha256": SHA_D,
                    "pcm_payload_sha256": SHA_E,
                    "hypothesis": "synthetic fixture",
                    "status": "ok",
                    "error_code": None,
                }
            ],
        }

    def _engineering_output(self) -> dict[str, object]:
        artifacts = [
            {
                "component_role": "weights",
                "sha256": SHA_F,
                "size_bytes": 1024,
                "st_dev": 1,
                "st_ino": 2,
                "mode": 0o100400,
                "nlink": 1,
            }
        ]
        samples = [
            {
                "monotonic_ns": index * 5_000_000_000,
                "rss_bytes": 1_000_000 + index,
                "pss_bytes": 900_000 + index,
                "thermal_status": 0,
                "heartbeat_index": index,
                "runner_alive": True,
                "decoder_active": index > 0,
                "active_clip_id": CLIP_ID if index > 0 else None,
                "active_decode_progress": index,
                "completed_clip_count": index,
                "completed_loops": index,
                "completed_clips_in_current_loop": 0,
                "successful_loops": index,
                "last_verified_clip_id": CLIP_ID if index > 0 else None,
            }
            for index in range(3)
        ]
        loops = [
            {
                "loop_index": index,
                "monotonic_start_ns": (index - 1) * 5_000_000_000,
                "monotonic_end_ns": index * 5_000_000_000,
                "ordered_clip_ids_sha256": canonical_sha256([CLIP_ID]),
                "clip_count": 1,
                "cumulative_decoded_clip_count": index,
                "successful": True,
            }
            for index in (1, 2)
        ]
        telemetry_without_hash = {
            "sample_interval_ms": 5_000,
            "duration_ns": 10_000_000_000,
            "samples": samples,
            "loop_count": 2,
            "successful_loop_count": 2,
            "total_decoded_clip_count": 2,
            "loop_proofs": loops,
        }
        telemetry = {
            **telemetry_without_hash,
            "summary_sha256": canonical_sha256(telemetry_without_hash),
        }
        bindings = {
            "benchmark_evidence_sha256": SHA_A,
            "public_plan_sha256": SHA_B,
            "registry_sha256": "1" * 64,
            "normalization_sha256": "2" * 64,
            "selection_config_sha256": "3" * 64,
            "runner_build_sha256": "4" * 64,
            "apk_sha256": "5" * 64,
            "app_signing_cert_sha256": "6" * 64,
            "device_identity_commitment_sha256": "7" * 64,
        }
        return {
            "schema_version": "2.0",
            "run_id": RUN_ID,
            "dataset_id": DATASET_ID,
            "model_alias": MODEL_ALIAS,
            "decoder_plan_sha256": canonical_sha256(self.decoder_plan),
            "accuracy_output_sha256": canonical_sha256(self.accuracy),
            "clock_source": "android_elapsed_realtime_nanos",
            "trusted_runner_status": "phase_b_development_only",
            "bindings": bindings,
            "pcm_set_sha256": canonical_sha256(
                [{"clip_id": CLIP_ID, "wav": SHA_D, "payload": SHA_E}]
            ),
            "artifact_measurements": artifacts,
            "artifact_set_sha256": canonical_sha256(artifacts),
            "clip_measurements": [
                {
                    "clip_id": CLIP_ID,
                    "wav_file_sha256_before": SHA_D,
                    "wav_file_sha256_after": SHA_D,
                    "pcm_payload_sha256_before": SHA_E,
                    "pcm_payload_sha256_after": SHA_E,
                    "pcm_payload_bytes": 320,
                    "st_dev_before": 8,
                    "st_dev_after": 8,
                    "st_ino_before": 9,
                    "st_ino_after": 9,
                    "mode_before": 0o100400,
                    "mode_after": 0o100400,
                    "nlink_before": 1,
                    "nlink_after": 1,
                    "monotonic_stop_ns": 1_000_000_000,
                    "monotonic_final_ns": 1_100_000_000,
                    "stop_to_final_ns": 100_000_000,
                    "run_state": "cold",
                    "status": "ok",
                    "error_code": None,
                }
            ],
            "runtime": {
                "total_resource_bytes": 1024,
                "cold_latency_ns": [100_000_000],
                "warm_latency_ns": [],
                "decode_probe_peak_rss_bytes": 1_000_001,
                "decode_probe_peak_pss_bytes": 900_001,
                "peak_rss_bytes": 1_000_002,
                "peak_pss_bytes": 900_002,
                "oom_count": 0,
                "crash_count": 0,
                "success_count": 1,
            },
            "telemetry": telemetry,
        }

    def _development_certificate(
        self,
    ) -> tuple[ec.EllipticCurvePrivateKey, x509.Certificate]:
        private_key = ec.generate_private_key(ec.SECP256R1())
        subject = issuer = x509.Name(
            [x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic development key")]
        )
        now = datetime.datetime.now(datetime.timezone.utc)
        certificate = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(issuer)
            .public_key(private_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=1))
            .not_valid_after(now + datetime.timedelta(days=1))
            .sign(private_key, hashes.SHA256())
        )
        return private_key, certificate

    def _signed_envelope(
        self,
        *,
        profile: str = "development_fixture",
        emulator: bool = True,
        security_level_claim: str = "software",
    ) -> dict[str, object]:
        commitments = build_attestation_commitments(
            self.decoder_receipt,
            self.decoder_plan,
            self.accuracy,
            self.engineering,
        )
        challenge_sha256 = hashlib.sha256(
            b"kittyecho-asr-keystore-challenge-v1\x00"
            + HOST_CHALLENGE
            + json.dumps(
                commitments,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        signed_payload = {
            "schema_version": "1.0",
            "run_id": RUN_ID,
            "model_alias": MODEL_ALIAS,
            "protocol_profile": profile,
            "commitments": commitments,
            "host_challenge_sha256": hashlib.sha256(HOST_CHALLENGE).hexdigest(),
            "attestation_challenge_sha256": challenge_sha256,
            "runtime_claims": {
                "physical_device": not emulator,
                "emulator": emulator,
                "local_key_security_level": security_level_claim,
            },
        }
        signature = self.private_key.sign(
            attestation_message_bytes(signed_payload),
            ec.ECDSA(hashes.SHA256()),
        )
        return {
            "schema_version": "1.0",
            "attestation_format": "android-keystore-key-attestation-v1",
            "signed_payload": signed_payload,
            "signature_algorithm": "SHA256withECDSA",
            "signature_base64": base64.b64encode(signature).decode("ascii"),
            "certificate_chain_der_base64": [
                base64.b64encode(encoded).decode("ascii")
                for encoded in self.certificate_chain_der
            ],
        }

    def _hardware_certificate_chain(
        self,
        attestation_challenge_sha256: str,
    ) -> tuple[
        ec.EllipticCurvePrivateKey,
        list[bytes],
        str,
    ]:
        root_key = ec.generate_private_key(ec.SECP256R1())
        leaf_key = ec.generate_private_key(ec.SECP256R1())
        root_name = x509.Name(
            [x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic attestation root")]
        )
        leaf_name = x509.Name(
            [x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic attested key")]
        )
        now = datetime.datetime.now(datetime.timezone.utc)
        root = (
            x509.CertificateBuilder()
            .subject_name(root_name)
            .issuer_name(root_name)
            .public_key(root_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=1))
            .not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(
                x509.BasicConstraints(ca=True, path_length=1),
                critical=True,
            )
            .sign(root_key, hashes.SHA256())
        )
        extension = _android_attestation_extension(
            challenge=bytes.fromhex(attestation_challenge_sha256),
            package_name="com.wordtaker.keyboard.asrbenchmark.runner",
            version_code=17,
            signing_cert_sha256="6" * 64,
        )
        leaf = (
            x509.CertificateBuilder()
            .subject_name(leaf_name)
            .issuer_name(root_name)
            .public_key(leaf_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=1))
            .not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(
                x509.UnrecognizedExtension(
                    ANDROID_ATTESTATION_OID,
                    extension,
                ),
                critical=False,
            )
            .sign(root_key, hashes.SHA256())
        )
        root_spki = root.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        return (
            leaf_key,
            [
                leaf.public_bytes(serialization.Encoding.DER),
                root.public_bytes(serialization.Encoding.DER),
            ],
            hashlib.sha256(root_spki).hexdigest(),
        )

    def _write_document(self, path: Path, document: object) -> None:
        path.write_text(
            json.dumps(document, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
        )

    def _write_raw_inputs(self) -> None:
        self._write_document(self.accuracy_path, self.accuracy)
        self._write_document(self.engineering_path, self.engineering)
        self._write_document(self.envelope_path, self.envelope)

    def _verify(
        self,
        *,
        policy: AndroidAttestationPolicy | None = None,
    ) -> object:
        return verify_and_commit_android_bundle(
            decoder_plan_receipt_path=self.decoder_receipt_path,
            accuracy_payload_path=self.accuracy_path,
            engineering_payload_path=self.engineering_path,
            attestation_envelope_path=self.envelope_path,
            accuracy_receipt_path=self.accuracy_receipt_path,
            engineering_receipt_path=self.engineering_receipt_path,
            attestation_receipt_path=self.attestation_receipt_path,
            repo_root=self.repo_root,
            policy=policy
            or AndroidAttestationPolicy.development(HOST_CHALLENGE),
        )

    def test_development_signature_closes_content_addressed_receipt_loop(
        self,
    ) -> None:
        result = self._verify()

        self.assertTrue(result.signature_verified)
        self.assertFalse(result.formal_eligible)
        self.assertFalse(result.product_decision_eligible)
        self.assertFalse(result.adversarial_same_uid_resistant)
        self.assertIn(
            "trusted_execution_measurement_attestation_required",
            result.blockers,
        )
        verified_accuracy = load_development_synthetic_android_accuracy(
            self.decoder_receipt_path,
            self.accuracy_receipt_path,
            self.engineering_receipt_path,
            self.attestation_receipt_path,
            policy=AndroidAccuracyLoadPolicy.DEVELOPMENT_SYNTHETIC,
            expected_run_id=RUN_ID,
            expected_evidence_sha256=SHA_A,
            expected_plan_sha256=SHA_B,
        )
        self.assertEqual(verified_accuracy, self.accuracy)
        _engineering, engineering_receipt = load_committed_json_artifact(
            self.engineering_receipt_path,
            expected_kind="android-engineering-output",
        )
        committed, attestation_receipt = load_committed_json_artifact(
            self.attestation_receipt_path,
            expected_kind="android-attestation-verification",
        )
        self.assertEqual(
            committed["engineering_receipt_commit_sha256"],
            engineering_receipt["commit_sha256"],
        )
        self.assertFalse(committed["formal_eligible"])
        self.assertEqual(attestation_receipt["run_id"], RUN_ID)

    def test_generic_commit_rejects_reserved_android_artifact_kinds(self) -> None:
        for index, artifact_kind in enumerate(
            (
                "android-accuracy-output",
                "android-engineering-output",
                "android-attestation-verification",
            )
        ):
            with self.subTest(artifact_kind=artifact_kind):
                forged_path = (
                    self.private_root / f"forged-{index}.receipt.json"
                )
                with self.assertRaisesRegex(
                    ContractError,
                    "reserved Android artifact kind",
                ):
                    commit_json_artifact(
                        forged_path,
                        {"run_id": RUN_ID},
                        artifact_kind=artifact_kind,
                        run_id=RUN_ID,
                        evidence_sha256=SHA_A,
                        plan_sha256=SHA_B,
                        validate_evidence=lambda: None,
                    )
                self.assertFalse(forged_path.exists())

    def test_forged_minimal_formal_accuracy_is_not_consumable(self) -> None:
        forged_accuracy_path = (
            self.private_root / "forged-accuracy.receipt.json"
        )
        forged_verification_path = (
            self.private_root / "forged-verification.receipt.json"
        )
        forged_accuracy_receipt = commit_json_artifact(
            forged_accuracy_path,
            {"run_id": RUN_ID},
            artifact_kind="forged-android-accuracy",
            run_id=RUN_ID,
            evidence_sha256=SHA_A,
            plan_sha256=SHA_B,
            validate_evidence=lambda: None,
        )
        commit_json_artifact(
            forged_verification_path,
            {
                "run_id": RUN_ID,
                "protocol_profile": "production_confirmation",
                "signature_verified": True,
                "formal_eligible": True,
                "product_decision_eligible": True,
                "accuracy_payload_receipt_commit_sha256": (
                    forged_accuracy_receipt["commit_sha256"]
                ),
            },
            artifact_kind="forged-android-verification",
            run_id=RUN_ID,
            evidence_sha256=SHA_A,
            plan_sha256=SHA_B,
            validate_evidence=lambda: None,
        )

        with self.assertRaisesRegex(
            ContractError,
            "trusted_external_observer_receipt_required",
        ):
            load_verified_android_accuracy(
                forged_verification_path,
                forged_accuracy_path,
                expected_run_id=RUN_ID,
                expected_evidence_sha256=SHA_A,
                expected_plan_sha256=SHA_B,
            )

    def test_formal_accuracy_consumer_requires_external_observer_receipt(
        self,
    ) -> None:
        self._verify()

        with self.assertRaisesRegex(
            ContractError,
            "trusted_external_observer_receipt_required",
        ):
            load_verified_android_accuracy(
                self.attestation_receipt_path,
                self.accuracy_receipt_path,
                expected_run_id=RUN_ID,
                expected_evidence_sha256=SHA_A,
                expected_plan_sha256=SHA_B,
            )

    def test_development_loader_requires_explicit_synthetic_policy(self) -> None:
        self._verify()

        with self.assertRaisesRegex(
            ContractError,
            "development_synthetic_accuracy_policy_required",
        ):
            load_development_synthetic_android_accuracy(
                self.decoder_receipt_path,
                self.accuracy_receipt_path,
                self.engineering_receipt_path,
                self.attestation_receipt_path,
                policy="development_synthetic",  # type: ignore[arg-type]
                expected_run_id=RUN_ID,
                expected_evidence_sha256=SHA_A,
                expected_plan_sha256=SHA_B,
            )

    def test_development_loader_revalidates_schemas_and_commitments(
        self,
    ) -> None:
        self._verify()
        documents: dict[str, dict[str, Any]] = {}
        receipts: dict[str, dict[str, Any]] = {}
        for name, path, kind in (
            ("decoder", self.decoder_receipt_path, "decoder-plan"),
            ("accuracy", self.accuracy_receipt_path, "android-accuracy-output"),
            (
                "engineering",
                self.engineering_receipt_path,
                "android-engineering-output",
            ),
            (
                "verification",
                self.attestation_receipt_path,
                "android-attestation-verification",
            ),
        ):
            document, receipt = load_committed_json_artifact(
                path,
                expected_kind=kind,
            )
            documents[name] = document
            receipts[name] = receipt

        attacks: list[
            tuple[
                str,
                dict[str, dict[str, Any]],
                dict[str, dict[str, Any]],
                str,
            ]
        ] = []

        minimal_accuracy = copy.deepcopy(documents)
        minimal_accuracy["accuracy"] = {"run_id": RUN_ID}
        attacks.append(
            ("minimal_accuracy", minimal_accuracy, receipts, "fields")
        )

        minimal_verification = copy.deepcopy(documents)
        minimal_verification["verification"] = {"run_id": RUN_ID}
        attacks.append(
            (
                "minimal_verification",
                minimal_verification,
                receipts,
                "fields",
            )
        )

        true_eligibility = copy.deepcopy(documents)
        true_eligibility["verification"]["formal_eligible"] = True
        true_eligibility["verification"]["product_decision_eligible"] = True
        attacks.append(
            (
                "self_reported_eligibility",
                true_eligibility,
                receipts,
                "eligibility",
            )
        )

        source_commit = copy.deepcopy(documents)
        source_commit["verification"][
            "accuracy_payload_receipt_commit_sha256"
        ] = SHA_C
        attacks.append(
            (
                "source_receipt_commit",
                source_commit,
                receipts,
                "source receipt commitment",
            )
        )

        build_commitment = copy.deepcopy(documents)
        build_commitment["engineering"]["bindings"][
            "runner_build_sha256"
        ] = SHA_C
        attacks.append(
            (
                "runner_build",
                build_commitment,
                receipts,
                "commitments differ",
            )
        )

        device_commitment = copy.deepcopy(documents)
        device_commitment["engineering"]["bindings"][
            "device_identity_commitment_sha256"
        ] = SHA_C
        attacks.append(
            (
                "device_identity",
                device_commitment,
                receipts,
                "commitments differ",
            )
        )

        pcm_commitment = copy.deepcopy(documents)
        pcm_commitment["engineering"]["pcm_set_sha256"] = SHA_C
        attacks.append(
            ("pcm_set", pcm_commitment, receipts, "PCM-set hash")
        )

        artifact_commitment = copy.deepcopy(documents)
        artifact_commitment["engineering"]["artifact_set_sha256"] = SHA_C
        attacks.append(
            (
                "artifact_set",
                artifact_commitment,
                receipts,
                "artifact-set hash",
            )
        )

        telemetry_commitment = copy.deepcopy(documents)
        telemetry_commitment["engineering"]["telemetry"][
            "summary_sha256"
        ] = SHA_C
        attacks.append(
            (
                "telemetry",
                telemetry_commitment,
                receipts,
                "summary hash",
            )
        )

        cross_run_receipts = copy.deepcopy(receipts)
        cross_run_receipts["engineering"]["run_id"] = "run_ffffffffffff"
        attacks.append(
            (
                "cross_run",
                documents,
                cross_run_receipts,
                "different run",
            )
        )

        paths = {
            self.decoder_receipt_path: "decoder",
            self.accuracy_receipt_path: "accuracy",
            self.engineering_receipt_path: "engineering",
            self.attestation_receipt_path: "verification",
        }
        for name, attacked_documents, attacked_receipts, message in attacks:
            with self.subTest(attack=name):

                def load_attacked(
                    path: Path,
                    **_expected: object,
                ) -> tuple[dict[str, Any], dict[str, Any]]:
                    key = paths[Path(path)]
                    return (
                        copy.deepcopy(attacked_documents[key]),
                        copy.deepcopy(attacked_receipts[key]),
                    )

                with mock.patch(
                    "kittyecho_asr_bench.android_attestation."
                    "load_committed_json_artifact",
                    side_effect=load_attacked,
                ):
                    with self.assertRaisesRegex(ContractError, message):
                        load_development_synthetic_android_accuracy(
                            self.decoder_receipt_path,
                            self.accuracy_receipt_path,
                            self.engineering_receipt_path,
                            self.attestation_receipt_path,
                            policy=(
                                AndroidAccuracyLoadPolicy.DEVELOPMENT_SYNTHETIC
                            ),
                            expected_run_id=RUN_ID,
                            expected_evidence_sha256=SHA_A,
                            expected_plan_sha256=SHA_B,
                        )

    def test_handmade_generic_accuracy_receipt_is_not_development_input(
        self,
    ) -> None:
        self._verify()
        handmade_path = self.private_root / "handmade-accuracy.receipt.json"
        commit_json_artifact(
            handmade_path,
            self.accuracy,
            artifact_kind="generic-android-accuracy",
            run_id=RUN_ID,
            evidence_sha256=SHA_A,
            plan_sha256=SHA_B,
            validate_evidence=lambda: None,
        )

        with self.assertRaisesRegex(ContractError, "kind"):
            load_development_synthetic_android_accuracy(
                self.decoder_receipt_path,
                handmade_path,
                self.engineering_receipt_path,
                self.attestation_receipt_path,
                policy=AndroidAccuracyLoadPolicy.DEVELOPMENT_SYNTHETIC,
                expected_run_id=RUN_ID,
                expected_evidence_sha256=SHA_A,
                expected_plan_sha256=SHA_B,
            )

    def test_score_cli_never_imports_development_synthetic_loader(self) -> None:
        cli_path = (
            Path(__file__).resolve().parents[1]
            / "src"
            / "kittyecho_asr_bench"
            / "cli.py"
        )
        tree = ast.parse(cli_path.read_text(encoding="utf-8"))
        imported = {
            alias.name
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom)
            and node.module == "android_attestation"
            for alias in node.names
        }
        self.assertIn("load_verified_android_accuracy", imported)
        self.assertNotIn(
            "load_development_synthetic_android_accuracy",
            imported,
        )

    def test_prediction_order_duplicate_and_pcm_mismatch_fail_closed(self) -> None:
        duplicate = copy.deepcopy(self.accuracy)
        duplicate["predictions"].append(copy.deepcopy(duplicate["predictions"][0]))
        wrong_pcm = copy.deepcopy(self.accuracy)
        wrong_pcm["predictions"][0]["pcm_sha256"] = SHA_C
        for index, (accuracy, message) in enumerate(
            (
                (duplicate, "duplicate|order|count"),
                (wrong_pcm, "PCM|pcm|hash"),
            )
        ):
            with self.subTest(attack=index):
                self._write_document(self.accuracy_path, accuracy)
                with self.assertRaisesRegex(ContractError, message):
                    self._verify()
                self.assertFalse(self.attestation_receipt_path.exists())
                self._write_document(self.accuracy_path, self.accuracy)

        two_clip_plan = copy.deepcopy(self.decoder_plan)
        two_clip_plan["clips"].append(
            {
                **two_clip_plan["clips"][0],
                "clip_id": "clip_ffffffffffff",
            }
        )
        wrong_order = copy.deepcopy(self.accuracy)
        second_prediction = {
            **wrong_order["predictions"][0],
            "clip_id": "clip_ffffffffffff",
        }
        wrong_order["predictions"] = [
            second_prediction,
            wrong_order["predictions"][0],
        ]
        wrong_order["decoder_plan_sha256"] = canonical_sha256(two_clip_plan)
        with self.assertRaisesRegex(ContractError, "order"):
            _validate_accuracy(wrong_order, two_clip_plan)

    def test_decoder_and_accuracy_contract_attack_matrix_fails_closed(self) -> None:
        plan_attacks = []
        for field, value in (
            ("schema_version", "2.0"),
            ("run_id", "run_invalid"),
            ("dataset_id", "dataset_invalid"),
            ("model_alias", "baseline"),
            ("pcm_contract_id", "different-pcm"),
        ):
            attack = copy.deepcopy(self.decoder_plan)
            attack[field] = value
            plan_attacks.append(attack)
        invalid_clip = copy.deepcopy(self.decoder_plan)
        invalid_clip["clips"][0]["clip_id"] = "not-anonymous"
        plan_attacks.append(invalid_clip)
        duplicate_clip = copy.deepcopy(self.decoder_plan)
        duplicate_clip["clips"].append(copy.deepcopy(duplicate_clip["clips"][0]))
        plan_attacks.append(duplicate_clip)
        relative_path = copy.deepcopy(self.decoder_plan)
        relative_path["clips"][0]["audio_path"] = "relative.wav"
        plan_attacks.append(relative_path)
        for index, attack in enumerate(plan_attacks):
            with self.subTest(plan_attack=index):
                with self.assertRaises(ContractError):
                    _validate_decoder_plan(attack)

        accuracy_attacks = []
        for field, value in (
            ("schema_version", "2.0"),
            ("run_id", "run_ffffffffffff"),
            ("decoder_contract_id", "different-decoder"),
            ("decoder_plan_sha256", SHA_A),
            ("reference_accessed", True),
        ):
            attack = copy.deepcopy(self.accuracy)
            attack[field] = value
            accuracy_attacks.append(attack)
        for field, value in (
            ("hypothesis", "x" * 100_001),
            ("status", "fabricated"),
            ("error_code", "fabricated"),
        ):
            attack = copy.deepcopy(self.accuracy)
            attack["predictions"][0][field] = value
            accuracy_attacks.append(attack)
        conflict = copy.deepcopy(self.accuracy)
        conflict["predictions"][0]["status"] = "error"
        conflict["predictions"][0]["error_code"] = None
        accuracy_attacks.append(conflict)
        for index, attack in enumerate(accuracy_attacks):
            with self.subTest(accuracy_attack=index):
                with self.assertRaises(ContractError):
                    _validate_accuracy(attack, self.decoder_plan)

    def test_runtime_and_telemetry_attack_matrix_fails_closed(self) -> None:
        artifacts = self.engineering["artifact_measurements"]
        measurements = self.engineering["clip_measurements"]
        runtime_attacks = []
        for field, value in (
            ("total_resource_bytes", 0),
            ("cold_latency_ns", [1]),
            ("success_count", 0),
            ("oom_count", 1),
            ("crash_count", 1),
        ):
            attack = copy.deepcopy(self.engineering["runtime"])
            attack[field] = value
            runtime_attacks.append(attack)
        for index, attack in enumerate(runtime_attacks):
            with self.subTest(runtime_attack=index):
                with self.assertRaises(ContractError):
                    _validate_runtime(
                        attack,
                        artifacts=artifacts,
                        clip_measurements=measurements,
                    )

        telemetry_attacks = []
        too_few_loops = copy.deepcopy(self.engineering["telemetry"])
        too_few_loops["loop_proofs"] = too_few_loops["loop_proofs"][:1]
        telemetry_attacks.append(too_few_loops)
        mutation_paths = (
            ("loop_index", 2),
            ("monotonic_end_ns", -1),
            ("ordered_clip_ids_sha256", SHA_A),
            ("successful", False),
        )
        for field, value in mutation_paths:
            attack = copy.deepcopy(self.engineering["telemetry"])
            attack["loop_proofs"][0][field] = value
            telemetry_attacks.append(attack)
        interval = copy.deepcopy(self.engineering["telemetry"])
        interval["samples"][1]["monotonic_ns"] += 300_000_000
        telemetry_attacks.append(interval)
        heartbeat = copy.deepcopy(self.engineering["telemetry"])
        heartbeat["samples"][1]["runner_alive"] = False
        telemetry_attacks.append(heartbeat)
        progress = copy.deepcopy(self.engineering["telemetry"])
        progress["samples"][1]["active_decode_progress"] = 2
        progress["samples"][2]["active_decode_progress"] = 1
        telemetry_attacks.append(progress)
        counter_backwards = copy.deepcopy(self.engineering["telemetry"])
        counter_backwards["samples"][1]["completed_clip_count"] = 2
        counter_backwards["samples"][2]["completed_clip_count"] = 1
        telemetry_attacks.append(counter_backwards)
        counter_proof = copy.deepcopy(self.engineering["telemetry"])
        counter_proof["samples"][1]["completed_loops"] = 0
        counter_proof["samples"][1]["successful_loops"] = 0
        telemetry_attacks.append(counter_proof)
        thermal = copy.deepcopy(self.engineering["telemetry"])
        thermal["samples"][1]["thermal_status"] = 7
        telemetry_attacks.append(thermal)
        one_sample = copy.deepcopy(self.engineering["telemetry"])
        one_sample["samples"] = one_sample["samples"][:1]
        telemetry_attacks.append(one_sample)
        duration = copy.deepcopy(self.engineering["telemetry"])
        duration["duration_ns"] += 1_000_000_000
        telemetry_attacks.append(duration)
        for field, value in (
            ("loop_count", 3),
            ("successful_loop_count", 1),
            ("total_decoded_clip_count", 1),
        ):
            attack = copy.deepcopy(self.engineering["telemetry"])
            attack[field] = value
            telemetry_attacks.append(attack)
        peak = copy.deepcopy(self.engineering["telemetry"])
        peak["samples"][-1]["rss_bytes"] += 1
        telemetry_attacks.append(peak)
        summary = copy.deepcopy(self.engineering["telemetry"])
        summary["summary_sha256"] = SHA_A
        telemetry_attacks.append(summary)
        for index, attack in enumerate(telemetry_attacks):
            with self.subTest(telemetry_attack=index):
                with self.assertRaises(ContractError):
                    _validate_telemetry(
                        attack,
                        decoder_plan=self.decoder_plan,
                        runtime=self.engineering["runtime"],
                    )

    def test_runtime_peak_contract_uses_exact_decode_and_telemetry_maximum(
        self,
    ) -> None:
        artifacts = self.engineering["artifact_measurements"]
        measurements = self.engineering["clip_measurements"]
        runtime = copy.deepcopy(self.engineering["runtime"])
        runtime["decode_probe_peak_rss_bytes"] = 2_000_000
        runtime["decode_probe_peak_pss_bytes"] = 800_000
        runtime["peak_rss_bytes"] = 2_000_000
        runtime["peak_pss_bytes"] = 900_002

        self.assertEqual(
            _validate_runtime(
                runtime,
                artifacts=artifacts,
                clip_measurements=measurements,
            ),
            runtime,
        )
        self.assertEqual(
            _validate_telemetry(
                self.engineering["telemetry"],
                decoder_plan=self.decoder_plan,
                runtime=runtime,
            ),
            self.engineering["telemetry"],
        )

        forged_probe = copy.deepcopy(runtime)
        forged_probe["decode_probe_peak_rss_bytes"] = 2_000_001
        with self.assertRaisesRegex(ContractError, "peak|RSS"):
            _validate_runtime(
                forged_probe,
                artifacts=artifacts,
                clip_measurements=measurements,
            )

        forged_aggregate = copy.deepcopy(runtime)
        forged_aggregate["peak_pss_bytes"] += 1
        with self.assertRaisesRegex(ContractError, "peak|PSS"):
            _validate_telemetry(
                self.engineering["telemetry"],
                decoder_plan=self.decoder_plan,
                runtime=forged_aggregate,
            )

    def test_progress_trace_is_derived_from_frozen_loop_and_clip_state(
        self,
    ) -> None:
        telemetry = self.engineering["telemetry"]
        runtime = self.engineering["runtime"]
        self.assertEqual(
            _validate_telemetry(
                telemetry,
                decoder_plan=self.decoder_plan,
                runtime=runtime,
            ),
            telemetry,
        )

        attacks = []
        first_sample_final = copy.deepcopy(telemetry)
        final_state = first_sample_final["samples"][-1]
        for field in (
            "decoder_active",
            "active_clip_id",
            "active_decode_progress",
            "completed_clip_count",
            "completed_loops",
            "completed_clips_in_current_loop",
            "successful_loops",
            "last_verified_clip_id",
        ):
            first_sample_final["samples"][0][field] = final_state[field]
        attacks.append(first_sample_final)

        skipped_clip = copy.deepcopy(telemetry)
        skipped_clip["samples"][1]["active_decode_progress"] = 2
        skipped_clip["samples"][1]["completed_clip_count"] = 2
        skipped_clip["samples"][1]["completed_loops"] = 2
        skipped_clip["samples"][1]["successful_loops"] = 2
        attacks.append(skipped_clip)

        repeated_clip = copy.deepcopy(telemetry)
        repeated_clip["samples"][1]["last_verified_clip_id"] = None
        attacks.append(repeated_clip)

        loop_clip_mismatch = copy.deepcopy(telemetry)
        loop_clip_mismatch["samples"][1][
            "completed_clips_in_current_loop"
        ] = 1
        attacks.append(loop_clip_mismatch)

        active_clip_mismatch = copy.deepcopy(telemetry)
        active_clip_mismatch["samples"][1][
            "active_clip_id"
        ] = "clip_ffffffffffff"
        attacks.append(active_clip_mismatch)

        for index, attack in enumerate(attacks):
            without_hash = dict(attack)
            without_hash.pop("summary_sha256", None)
            attack["summary_sha256"] = canonical_sha256(without_hash)
            with self.subTest(progress_attack=index):
                with self.assertRaisesRegex(
                    ContractError,
                    "first|progress|clip|loop|state|counter",
                ):
                    _validate_telemetry(
                        attack,
                        decoder_plan=self.decoder_plan,
                        runtime=runtime,
                    )

    def test_progress_trace_allows_multiple_verified_events_between_samples(
        self,
    ) -> None:
        second_clip = "clip_abcdefabcdef"
        decoder_plan = copy.deepcopy(self.decoder_plan)
        decoder_plan["clips"].append(
            {
                **copy.deepcopy(decoder_plan["clips"][0]),
                "clip_id": second_clip,
            }
        )
        order_sha256 = canonical_sha256([CLIP_ID, second_clip])
        samples = [
            {
                "monotonic_ns": 0,
                "rss_bytes": 1_000_000,
                "pss_bytes": 900_000,
                "thermal_status": 0,
                "heartbeat_index": 0,
                "runner_alive": True,
                "decoder_active": False,
                "active_clip_id": None,
                "active_decode_progress": 0,
                "completed_clip_count": 0,
                "completed_loops": 0,
                "completed_clips_in_current_loop": 0,
                "successful_loops": 0,
                "last_verified_clip_id": None,
            },
            {
                "monotonic_ns": 5_000_000_000,
                "rss_bytes": 1_000_001,
                "pss_bytes": 900_001,
                "thermal_status": 0,
                "heartbeat_index": 1,
                "runner_alive": True,
                "decoder_active": True,
                "active_clip_id": second_clip,
                "active_decode_progress": 3,
                "completed_clip_count": 3,
                "completed_loops": 1,
                "completed_clips_in_current_loop": 1,
                "successful_loops": 1,
                "last_verified_clip_id": CLIP_ID,
            },
            {
                "monotonic_ns": 10_000_000_000,
                "rss_bytes": 1_000_002,
                "pss_bytes": 900_002,
                "thermal_status": 0,
                "heartbeat_index": 2,
                "runner_alive": True,
                "decoder_active": True,
                "active_clip_id": CLIP_ID,
                "active_decode_progress": 4,
                "completed_clip_count": 4,
                "completed_loops": 2,
                "completed_clips_in_current_loop": 0,
                "successful_loops": 2,
                "last_verified_clip_id": second_clip,
            },
        ]
        loops = [
            {
                "loop_index": 1,
                "monotonic_start_ns": 1,
                "monotonic_end_ns": 4_000_000_000,
                "ordered_clip_ids_sha256": order_sha256,
                "clip_count": 2,
                "cumulative_decoded_clip_count": 2,
                "successful": True,
            },
            {
                "loop_index": 2,
                "monotonic_start_ns": 4_000_000_001,
                "monotonic_end_ns": 10_000_000_000,
                "ordered_clip_ids_sha256": order_sha256,
                "clip_count": 2,
                "cumulative_decoded_clip_count": 4,
                "successful": True,
            },
        ]
        without_hash = {
            "sample_interval_ms": 5_000,
            "duration_ns": 10_000_000_000,
            "samples": samples,
            "loop_count": 2,
            "successful_loop_count": 2,
            "total_decoded_clip_count": 4,
            "loop_proofs": loops,
        }
        telemetry = {
            **without_hash,
            "summary_sha256": canonical_sha256(without_hash),
        }

        self.assertEqual(
            _validate_telemetry(
                telemetry,
                decoder_plan=decoder_plan,
                runtime=self.engineering["runtime"],
            ),
            telemetry,
        )

    def test_empty_ten_minute_stability_trace_is_rejected(self) -> None:
        telemetry = copy.deepcopy(self.engineering["telemetry"])
        zero_state = telemetry["samples"][0]
        telemetry["samples"] = [
            {
                **copy.deepcopy(zero_state),
                "monotonic_ns": index * 5_000_000_000,
                "heartbeat_index": index,
            }
            for index in range(121)
        ]
        telemetry["duration_ns"] = 600_000_000_000
        without_hash = dict(telemetry)
        without_hash.pop("summary_sha256", None)
        telemetry["summary_sha256"] = canonical_sha256(without_hash)

        with self.assertRaisesRegex(
            ContractError,
            "idle|progress|loop|counter",
        ):
            _validate_telemetry(
                telemetry,
                decoder_plan=self.decoder_plan,
                runtime=self.engineering["runtime"],
            )

    def test_decoder_plan_identity_raw_bytes_and_canonical_digest_are_bound(
        self,
    ) -> None:
        decoder_plan = copy.deepcopy(self.decoder_plan)
        decoder_plan.pop("plan_id")
        decoder_plan["plan_id"] = f"plan_{canonical_sha256(decoder_plan)[:12]}"
        accuracy = copy.deepcopy(self.accuracy)
        accuracy["decoder_plan_sha256"] = canonical_sha256(decoder_plan)
        engineering = copy.deepcopy(self.engineering)
        engineering["decoder_plan_sha256"] = canonical_sha256(decoder_plan)
        engineering["accuracy_output_sha256"] = canonical_sha256(accuracy)

        commitments = build_attestation_commitments(
            self.decoder_receipt,
            decoder_plan,
            accuracy,
            engineering,
        )
        raw_plan_bytes = (
            json.dumps(
                decoder_plan,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode("utf-8")
        self.assertEqual(
            commitments["decoder_plan_raw_sha256"],
            hashlib.sha256(raw_plan_bytes).hexdigest(),
        )
        self.assertEqual(
            commitments["decoder_plan_id_sha256"],
            hashlib.sha256(
                decoder_plan["plan_id"].encode("utf-8")
            ).hexdigest(),
        )
        self.assertEqual(
            commitments["decoder_plan_sha256"],
            canonical_sha256(decoder_plan),
        )

    def test_tampered_payload_signature_and_commitments_are_rejected(self) -> None:
        attacks: list[tuple[str, object, str]] = []
        tampered_accuracy = copy.deepcopy(self.accuracy)
        tampered_accuracy["predictions"][0]["hypothesis"] = "tampered"
        attacks.append(("accuracy", tampered_accuracy, "commitment|hash"))

        tampered_engineering = copy.deepcopy(self.engineering)
        tampered_engineering["bindings"]["runner_build_sha256"] = SHA_C
        attacks.append(("engineering", tampered_engineering, "binding|commitment|hash"))

        bad_signature = copy.deepcopy(self.envelope)
        bad_signature["signature_base64"] = base64.b64encode(b"not-a-signature").decode(
            "ascii"
        )
        attacks.append(("envelope", bad_signature, "signature"))

        for index, (target, document, message) in enumerate(attacks):
            with self.subTest(attack=index):
                self._write_raw_inputs()
                target_path = {
                    "accuracy": self.accuracy_path,
                    "engineering": self.engineering_path,
                    "envelope": self.envelope_path,
                }[target]
                self._write_document(target_path, document)
                with self.assertRaisesRegex(ContractError, message):
                    self._verify()
                self.assertFalse(self.attestation_receipt_path.exists())

    def test_build_device_registry_normalization_and_selection_tamper_rejected(
        self,
    ) -> None:
        keys = (
            "runner_build_sha256",
            "apk_sha256",
            "app_signing_cert_sha256",
            "device_identity_commitment_sha256",
            "registry_sha256",
            "normalization_sha256",
            "selection_config_sha256",
        )
        for key in keys:
            with self.subTest(key=key):
                self._write_raw_inputs()
                envelope = copy.deepcopy(self.envelope)
                envelope["signed_payload"]["commitments"][key] = SHA_C
                self._write_document(self.envelope_path, envelope)
                with self.assertRaisesRegex(
                    ContractError, "commitment|binding|signature"
                ):
                    self._verify()

    def test_idle_stability_and_adapter_fabricated_metric_fields_are_rejected(
        self,
    ) -> None:
        idle = copy.deepcopy(self.engineering)
        idle["telemetry"]["samples"][1]["decoder_active"] = False
        idle["telemetry"]["samples"][1]["active_decode_progress"] = 1
        without_hash = dict(idle["telemetry"])
        del without_hash["summary_sha256"]
        idle["telemetry"]["summary_sha256"] = canonical_sha256(without_hash)
        self._write_document(self.engineering_path, idle)
        with self.assertRaisesRegex(ContractError, "active|idle|progress"):
            self._verify()

        fabricated = copy.deepcopy(self.accuracy)
        fabricated["predictions"][0]["stop_to_final_ns"] = 1
        self._write_raw_inputs()
        self._write_document(self.accuracy_path, fabricated)
        with self.assertRaisesRegex(ContractError, "field|accuracy|prediction"):
            self._verify()

    def test_software_or_emulator_claim_never_gets_formal_eligibility(self) -> None:
        self.envelope = self._signed_envelope(
            profile="production_confirmation",
            emulator=True,
            security_level_claim="software",
        )
        self._write_raw_inputs()
        result = self._verify(
            policy=AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=HOST_CHALLENGE
            )
        )

        self.assertFalse(result.formal_eligible)
        self.assertIn("hardware_key_attestation_required", result.blockers)
        self.assertIn("physical_device_required", result.blockers)
        self.assertIn(
            "trusted_execution_measurement_attestation_required",
            result.blockers,
        )

    def test_claiming_hardware_without_android_extension_is_not_trusted(self) -> None:
        self.envelope = self._signed_envelope(
            profile="exploration",
            emulator=False,
            security_level_claim="strongbox",
        )
        self._write_raw_inputs()
        result = self._verify(
            policy=AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=HOST_CHALLENGE
            )
        )

        self.assertFalse(result.hardware_key_verified)
        self.assertFalse(result.formal_eligible)
        self.assertIn("hardware_key_attestation_required", result.blockers)

    def test_formal_policy_freezes_expected_package_version_and_challenge(
        self,
    ) -> None:
        policy = AndroidAttestationPolicy.formal_no_go(
            expected_host_challenge=HOST_CHALLENGE,
            expected_version_code=17,
        )
        self.assertEqual(policy.expected_version_code, 17)
        with self.assertRaisesRegex(ContractError, "version"):
            AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=HOST_CHALLENGE,
                expected_version_code=0,
            )

    def test_policy_and_raw_json_boundary_attacks_fail_closed(self) -> None:
        for serial in (-1, True, "1"):
            with self.subTest(serial=serial):
                with self.assertRaisesRegex(ContractError, "serial"):
                    AndroidAttestationPolicy.formal_no_go(
                        expected_host_challenge=HOST_CHALLENGE,
                        revoked_certificate_serials=[serial],
                    )
        with self.assertRaisesRegex(ContractError, "root|sha256"):
            AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=HOST_CHALLENGE,
                trusted_root_spki_sha256=["not-a-sha"],
            )
        with self.assertRaisesRegex(ContractError, "package"):
            AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=HOST_CHALLENGE,
                expected_package_name="",
            )
        for challenge in (bytes(32), b"short", "not-bytes"):
            with self.subTest(challenge=type(challenge).__name__):
                with self.assertRaisesRegex(ContractError, "challenge"):
                    AndroidAttestationPolicy.development(challenge)

        malformed_documents = (
            b'{"schema_version":"1.0","schema_version":"2.0"}',
            b'{"value":NaN}',
            b"[1,2,3]",
            b"\xff",
        )
        for index, encoded in enumerate(malformed_documents):
            with self.subTest(raw_json=index):
                self.accuracy_path.write_bytes(encoded)
                with self.assertRaises(ContractError):
                    self._verify()
                self._write_document(self.accuracy_path, self.accuracy)

        self.accuracy_path.unlink()
        self.accuracy_path.symlink_to(self.engineering_path)
        with self.assertRaisesRegex(ContractError, "symlink|regular|readable"):
            self._verify()

    def test_valid_hardware_chain_application_and_boot_are_verified_but_no_go(
        self,
    ) -> None:
        commitments = build_attestation_commitments(
            self.decoder_receipt,
            self.decoder_plan,
            self.accuracy,
            self.engineering,
        )
        attestation_challenge_sha256 = hashlib.sha256(
            b"kittyecho-asr-keystore-challenge-v1\x00"
            + HOST_CHALLENGE
            + json.dumps(
                commitments,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        (
            self.private_key,
            self.certificate_chain_der,
            trusted_root,
        ) = self._hardware_certificate_chain(attestation_challenge_sha256)
        self.envelope = self._signed_envelope(
            profile="production_confirmation",
            emulator=False,
            security_level_claim="trusted_environment",
        )
        self._write_raw_inputs()

        result = self._verify(
            policy=AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=HOST_CHALLENGE,
                expected_version_code=17,
                trusted_root_spki_sha256=[trusted_root],
            )
        )

        self.assertTrue(result.signature_verified)
        self.assertTrue(result.certificate_chain_verified)
        self.assertTrue(result.hardware_key_verified)
        self.assertFalse(result.formal_eligible)
        self.assertFalse(result.product_decision_eligible)
        self.assertNotIn(
            "hardware_key_attestation_required",
            result.blockers,
        )
        self.assertNotIn(
            "trusted_attestation_certificate_chain_required",
            result.blockers,
        )
        self.assertIn(
            "trusted_execution_measurement_attestation_required",
            result.blockers,
        )

    def test_role_leakage_is_rejected_before_any_receipt(self) -> None:
        forbidden_documents = []
        with_reference = copy.deepcopy(self.engineering)
        with_reference["reference"] = "secret"
        forbidden_documents.append(with_reference)
        with_hypothesis = copy.deepcopy(self.engineering)
        with_hypothesis["clip_measurements"][0]["hypothesis"] = "secret"
        forbidden_documents.append(with_hypothesis)
        with_model_id = copy.deepcopy(self.engineering)
        with_model_id["model_id"] = "upstream/model"
        forbidden_documents.append(with_model_id)
        with_path = copy.deepcopy(self.engineering)
        with_path["artifact_measurements"][0]["filename"] = "/private/model.onnx"
        forbidden_documents.append(with_path)

        for index, document in enumerate(forbidden_documents):
            with self.subTest(document=index):
                self._write_raw_inputs()
                self._write_document(self.engineering_path, document)
                with self.assertRaisesRegex(
                    ContractError,
                    "forbidden|field|reference|hypothesis|model|path|filename",
                ):
                    self._verify()
                self.assertFalse(self.attestation_receipt_path.exists())

    def test_raw_or_orphan_blob_and_replaced_receipts_are_not_consumable(
        self,
    ) -> None:
        self._verify()
        accuracy_receipt_bytes = self.accuracy_receipt_path.read_bytes()

        marker, _receipt = load_committed_json_artifact(
            self.attestation_receipt_path,
            expected_kind="android-attestation-verification",
        )
        orphan_blob = Path(marker["accuracy_payload_receipt_commit_sha256"])
        with self.assertRaises((ContractError, OSError, ValueError)):
            load_development_synthetic_android_accuracy(
                self.decoder_receipt_path,
                orphan_blob,
                self.engineering_receipt_path,
                self.attestation_receipt_path,
                policy=AndroidAccuracyLoadPolicy.DEVELOPMENT_SYNTHETIC,
                expected_run_id=RUN_ID,
                expected_evidence_sha256=SHA_A,
                expected_plan_sha256=SHA_B,
            )

        replacement = self.private_root / "foreign-receipt.json"
        replacement.write_bytes(accuracy_receipt_bytes)
        replacement.chmod(0o400)
        os.replace(replacement, self.accuracy_receipt_path)
        with self.assertRaisesRegex(ContractError, "identity|inode|receipt"):
            load_development_synthetic_android_accuracy(
                self.decoder_receipt_path,
                self.accuracy_receipt_path,
                self.engineering_receipt_path,
                self.attestation_receipt_path,
                policy=AndroidAccuracyLoadPolicy.DEVELOPMENT_SYNTHETIC,
                expected_run_id=RUN_ID,
                expected_evidence_sha256=SHA_A,
                expected_plan_sha256=SHA_B,
            )

    def test_paths_must_be_repository_external_and_outputs_no_overwrite(self) -> None:
        in_repo = self.repo_root / "attestation.raw.json"
        self._write_document(in_repo, self.envelope)
        with self.assertRaisesRegex(ContractError, "outside|repository|private"):
            verify_and_commit_android_bundle(
                decoder_plan_receipt_path=self.decoder_receipt_path,
                accuracy_payload_path=self.accuracy_path,
                engineering_payload_path=self.engineering_path,
                attestation_envelope_path=in_repo,
                accuracy_receipt_path=self.accuracy_receipt_path,
                engineering_receipt_path=self.engineering_receipt_path,
                attestation_receipt_path=self.attestation_receipt_path,
                repo_root=self.repo_root,
                policy=AndroidAttestationPolicy.development(HOST_CHALLENGE),
            )

        self._verify()
        before = self.attestation_receipt_path.read_bytes()
        with self.assertRaisesRegex(ContractError, "already exists|overwrite"):
            self._verify()
        self.assertEqual(self.attestation_receipt_path.read_bytes(), before)

    def test_cli_verifies_and_commits_android_bundle_without_formal_eligibility(
        self,
    ) -> None:
        code = main(
            [
                "verify-android-bundle",
                "--decoder-plan-receipt",
                str(self.decoder_receipt_path),
                "--accuracy-payload",
                str(self.accuracy_path),
                "--engineering-payload",
                str(self.engineering_path),
                "--attestation-envelope",
                str(self.envelope_path),
                "--accuracy-receipt",
                str(self.accuracy_receipt_path),
                "--engineering-receipt",
                str(self.engineering_receipt_path),
                "--verification-receipt",
                str(self.attestation_receipt_path),
                "--repo-root",
                str(self.repo_root),
                "--host-challenge-hex",
                HOST_CHALLENGE.hex(),
                "--policy",
                "development",
            ]
        )

        self.assertEqual(code, 0)
        verification, _receipt = load_committed_json_artifact(
            self.attestation_receipt_path,
            expected_kind="android-attestation-verification",
        )
        self.assertFalse(verification["formal_eligible"])
        self.assertFalse(verification["product_decision_eligible"])
        self.assertFalse(verification["adversarial_same_uid_resistant"])

    def test_phase_b_contract_schemas_are_strict_json_documents(self) -> None:
        contract_root = Path(__file__).resolve().parents[1] / "contracts"
        for filename in (
            "android-accuracy-output.schema.json",
            "android-engineering-output.schema.json",
            "android-attestation-envelope.schema.json",
            "android-attestation-verification.schema.json",
        ):
            with self.subTest(filename=filename):
                schema = json.loads((contract_root / filename).read_text("utf-8"))
                self.assertEqual(
                    schema["$schema"],
                    "https://json-schema.org/draft/2020-12/schema",
                )
                self.assertEqual(schema["type"], "object")
                self.assertFalse(schema["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
