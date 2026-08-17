from __future__ import annotations

import copy
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from kittyecho_asr_bench.android_attestation import (
    AndroidAttestationPolicy,
    build_attestation_commitments,
    load_verified_android_accuracy,
)
from kittyecho_asr_bench.artifacts import (
    commit_json_artifact,
    load_committed_json_artifact,
)
from kittyecho_asr_bench.contracts import ContractError, canonical_sha256
from kittyecho_asr_bench.cli import main
from kittyecho_asr_bench.external_observer import (
    ExternalObserverPolicy,
    verify_and_commit_synthetic_observer_bundle,
)

import test_android_attestation as android_fixture


OBSERVER_CHALLENGE = b"\x91" * 32
HOST_CLOCK_BOOT_ID_SHA256 = "8" * 64
DEVICE_PHYSICAL_RAM_BYTES = 8 * 1024 * 1024 * 1024
MAX_PROCESS_MEMORY_BYTES = 4_000_000_000


class ExternalObserverSyntheticContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = android_fixture.AndroidAttestationHostVerifierTest(
            methodName=(
                "test_valid_hardware_chain_application_and_boot_are_verified_but_no_go"
            )
        )
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)

        commitments = build_attestation_commitments(
            self.fixture.decoder_receipt,
            self.fixture.decoder_plan,
            self.fixture.accuracy,
            self.fixture.engineering,
        )
        attestation_challenge_sha256 = hashlib.sha256(
            b"kittyecho-asr-keystore-challenge-v1\x00"
            + android_fixture.HOST_CHALLENGE
            + json.dumps(
                commitments,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        (
            self.fixture.private_key,
            self.fixture.certificate_chain_der,
            self.trusted_root,
        ) = self.fixture._hardware_certificate_chain(  # noqa: SLF001
            attestation_challenge_sha256
        )
        self.fixture.envelope = self.fixture._signed_envelope(  # noqa: SLF001
            profile="production_confirmation",
            emulator=False,
            security_level_claim="trusted_environment",
        )
        self.fixture._write_raw_inputs()  # noqa: SLF001
        self.fixture._verify(  # noqa: SLF001
            policy=AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=android_fixture.HOST_CHALLENGE,
                expected_version_code=17,
                trusted_root_spki_sha256=[self.trusted_root],
            )
        )

        self.decoder_plan, self.decoder_receipt = load_committed_json_artifact(
            self.fixture.decoder_receipt_path,
            expected_kind="decoder-plan",
        )
        self.accuracy, self.accuracy_receipt = load_committed_json_artifact(
            self.fixture.accuracy_receipt_path,
            expected_kind="android-accuracy-output",
        )
        self.engineering, self.engineering_receipt = load_committed_json_artifact(
            self.fixture.engineering_receipt_path,
            expected_kind="android-engineering-output",
        )
        self.verification, self.verification_receipt = (
            load_committed_json_artifact(
                self.fixture.attestation_receipt_path,
                expected_kind="android-attestation-verification",
            )
        )
        self.policy = ExternalObserverPolicy.synthetic_contract(
            observer_challenge=OBSERVER_CHALLENGE,
            android_attestation_policy=AndroidAttestationPolicy.formal_no_go(
                expected_host_challenge=android_fixture.HOST_CHALLENGE,
                expected_version_code=17,
                trusted_root_spki_sha256=[self.trusted_root],
            ),
            expected_package_name=(
                "com.wordtaker.keyboard.asrbenchmark.runner"
            ),
            expected_version_code=17,
            expected_apk_sha256="5" * 64,
            expected_signing_cert_sha256="6" * 64,
            expected_runner_build_sha256="4" * 64,
            expected_host_clock_boot_id_sha256=(
                HOST_CLOCK_BOOT_ID_SHA256
            ),
            expected_device_physical_ram_bytes=(
                DEVICE_PHYSICAL_RAM_BYTES
            ),
            expected_max_process_memory_bytes=(
                MAX_PROCESS_MEMORY_BYTES
            ),
        )
        self.observer_evidence_path = (
            self.fixture.private_root / "observer.synthetic.raw.json"
        )
        self.observer_receipt_path = (
            self.fixture.private_root / "observer.synthetic.receipt.json"
        )
        self.observer_evidence = self._observer_evidence()
        self._write_evidence(self.observer_evidence)

    def _repetition(self, index: int) -> dict[str, object]:
        base_ns = index * 20_000_000_000
        end_ns = base_ns + 10_000_000_000
        peak_rss = self.engineering["runtime"]["peak_rss_bytes"]
        peak_pss = self.engineering["runtime"]["peak_pss_bytes"]
        clip_timings = []
        for offset, measurement in enumerate(
            self.engineering["clip_measurements"]
        ):
            stop_ns = base_ns + offset * 2_000_000_000 + 1_000_000_000
            observed_latency_ns = measurement["stop_to_final_ns"] + 1_000_000
            clip_timings.append(
                {
                    "clip_id": measurement["clip_id"],
                    "monotonic_stop_ns": stop_ns,
                    "monotonic_final_ns": stop_ns + observed_latency_ns,
                    "stop_to_final_ns": observed_latency_ns,
                }
            )
        return {
            "repetition_index": index,
            "process_instance_sha256": hashlib.sha256(
                f"synthetic-process-{index}".encode("ascii")
            ).hexdigest(),
            "pid": 10_000 + index,
            "pid_start_ticks": 20_000 + index,
            "host_clock_boot_id_sha256": HOST_CLOCK_BOOT_ID_SHA256,
            "clean_process_start": True,
            "preexisting_process_detected": False,
            "process_exit_observed": True,
            "oom_kill_count": 0,
            "crash_count": 0,
            "host_monotonic_start_ns": base_ns,
            "host_monotonic_end_ns": end_ns,
            "external_clip_timings": clip_timings,
            "external_memory_samples": [
                {
                    "monotonic_ns": base_ns,
                    "rss_bytes": max(1, peak_rss // 2),
                    "pss_bytes": max(1, peak_pss // 2),
                },
                {
                    "monotonic_ns": base_ns + 5_000_000_000,
                    "rss_bytes": peak_rss,
                    "pss_bytes": peak_pss,
                },
                {
                    "monotonic_ns": end_ns,
                    "rss_bytes": peak_rss,
                    "pss_bytes": peak_pss,
                },
            ],
            "observed_peak_rss_bytes": peak_rss,
            "observed_peak_pss_bytes": peak_pss,
            "host_pcm_measurements": [
                {
                    "clip_id": clip["clip_id"],
                    "wav_file_sha256": clip["wav_file_sha256"],
                    "pcm_payload_sha256": clip["pcm_payload_sha256"],
                    "pcm_payload_bytes": clip["pcm_payload_bytes"],
                }
                for clip in self.decoder_plan["clips"]
            ],
            "host_artifact_measurements": copy.deepcopy(
                self.engineering["artifact_measurements"]
            ),
            "host_pcm_set_sha256": self.engineering["pcm_set_sha256"],
            "host_artifact_set_sha256": self.engineering[
                "artifact_set_sha256"
            ],
            "host_accuracy_output_sha256": canonical_sha256(self.accuracy),
            "host_apk_sha256": "5" * 64,
            "host_signing_cert_sha256": "6" * 64,
            "host_runner_build_sha256": "4" * 64,
        }

    def _observer_evidence(self) -> dict[str, object]:
        envelope_bytes = self.fixture.envelope_path.read_bytes()
        return {
            "schema_version": "1.0",
            "observer_mode": "synthetic_fixture_no_adb",
            "run_id": android_fixture.RUN_ID,
            "model_alias": android_fixture.MODEL_ALIAS,
            "observer_challenge_sha256": hashlib.sha256(
                OBSERVER_CHALLENGE
            ).hexdigest(),
            "host_clock": {
                "source": "host_monotonic_clock_v1",
                "boot_id_sha256": HOST_CLOCK_BOOT_ID_SHA256,
            },
            "host_resource_policy": {
                "device_physical_ram_bytes": DEVICE_PHYSICAL_RAM_BYTES,
                "max_process_memory_bytes": MAX_PROCESS_MEMORY_BYTES,
            },
            "known_release": {
                "package_name": (
                    "com.wordtaker.keyboard.asrbenchmark.runner"
                ),
                "version_code": 17,
                "apk_sha256": "5" * 64,
                "app_signing_cert_sha256": "6" * 64,
                "runner_build_sha256": "4" * 64,
            },
            "attestation_observation": {
                "certificate_chain_verified": True,
                "hardware_key_verified": True,
                "attestation_security_level": "trusted_environment",
                "challenge_matches": True,
                "application_matches": True,
                "locked_verified_boot": True,
                "attestation_envelope_raw_sha256": hashlib.sha256(
                    envelope_bytes
                ).hexdigest(),
            },
            "device_identity_commitment_sha256": "7" * 64,
            "source_receipt_commitments": {
                "phase_a_decoder_plan_receipt_commit_sha256": (
                    self.decoder_receipt["commit_sha256"]
                ),
                "accuracy_payload_receipt_commit_sha256": (
                    self.accuracy_receipt["commit_sha256"]
                ),
                "engineering_receipt_commit_sha256": (
                    self.engineering_receipt["commit_sha256"]
                ),
                "android_attestation_receipt_commit_sha256": (
                    self.verification_receipt["commit_sha256"]
                ),
            },
            "android_commitments": copy.deepcopy(
                self.verification["commitments"]
            ),
            "repetitions": [
                self._repetition(1),
                self._repetition(2),
            ],
        }

    def _write_evidence(self, value: object) -> None:
        self.observer_evidence_path.write_text(
            json.dumps(
                value,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
            encoding="utf-8",
        )

    def _verify(self) -> object:
        return verify_and_commit_synthetic_observer_bundle(
            decoder_plan_receipt_path=self.fixture.decoder_receipt_path,
            accuracy_receipt_path=self.fixture.accuracy_receipt_path,
            engineering_receipt_path=self.fixture.engineering_receipt_path,
            android_attestation_receipt_path=(
                self.fixture.attestation_receipt_path
            ),
            attestation_envelope_path=self.fixture.envelope_path,
            observer_evidence_path=self.observer_evidence_path,
            observer_receipt_path=self.observer_receipt_path,
            repo_root=self.fixture.repo_root,
            policy=self.policy,
        )

    def test_synthetic_observer_closes_contract_but_never_opens_formal_gate(
        self,
    ) -> None:
        result = self._verify()

        self.assertTrue(result.two_clean_process_repetitions_verified)
        self.assertTrue(result.independent_hash_contract_verified)
        self.assertFalse(result.formal_eligible)
        self.assertFalse(result.product_decision_eligible)
        self.assertFalse(result.adversarial_same_uid_resistant)
        self.assertIn(
            "real_adb_observer_execution_required",
            result.blockers,
        )
        document, receipt = load_committed_json_artifact(
            self.observer_receipt_path,
            expected_kind=(
                "android-external-observer-synthetic-verification"
            ),
            expected_evidence_sha256=android_fixture.SHA_A,
            expected_plan_sha256=android_fixture.SHA_B,
        )
        self.assertEqual(
            receipt["commit_sha256"],
            result.observer_receipt_commit_sha256,
        )
        self.assertEqual(
            document["source_receipt_commitments"],
            self.observer_evidence["source_receipt_commitments"],
        )
        self.assertFalse(document["formal_eligible"])
        self.assertFalse(document["product_decision_eligible"])
        self.assertFalse(document["adversarial_same_uid_resistant"])

        with self.assertRaisesRegex(
            ContractError,
            "trusted_external_observer_receipt_required",
        ):
            load_verified_android_accuracy(
                self.observer_receipt_path,
                self.fixture.accuracy_receipt_path,
                expected_run_id=android_fixture.RUN_ID,
                expected_evidence_sha256=android_fixture.SHA_A,
                expected_plan_sha256=android_fixture.SHA_B,
            )

    def test_development_cli_commits_only_synthetic_no_go_receipt(self) -> None:
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            exit_code = main(
                [
                    "verify-synthetic-observer-bundle",
                    "--decoder-plan-receipt",
                    str(self.fixture.decoder_receipt_path),
                    "--accuracy-receipt",
                    str(self.fixture.accuracy_receipt_path),
                    "--engineering-receipt",
                    str(self.fixture.engineering_receipt_path),
                    "--android-attestation-receipt",
                    str(self.fixture.attestation_receipt_path),
                    "--attestation-envelope",
                    str(self.fixture.envelope_path),
                    "--observer-evidence",
                    str(self.observer_evidence_path),
                    "--observer-receipt",
                    str(self.observer_receipt_path),
                    "--repo-root",
                    str(self.fixture.repo_root),
                    "--observer-challenge-hex",
                    OBSERVER_CHALLENGE.hex(),
                    "--host-challenge-hex",
                    android_fixture.HOST_CHALLENGE.hex(),
                    "--trusted-root-spki-sha256",
                    self.trusted_root,
                    "--expected-version-code",
                    "17",
                    "--expected-apk-sha256",
                    "5" * 64,
                    "--expected-signing-cert-sha256",
                    "6" * 64,
                    "--expected-runner-build-sha256",
                    "4" * 64,
                    "--expected-host-clock-boot-id-sha256",
                    HOST_CLOCK_BOOT_ID_SHA256,
                    "--expected-device-physical-ram-bytes",
                    str(DEVICE_PHYSICAL_RAM_BYTES),
                    "--expected-max-process-memory-bytes",
                    str(MAX_PROCESS_MEMORY_BYTES),
                ]
            )

        self.assertEqual(exit_code, 0)
        self.assertIn("observer_contract_synthetic", stdout.getvalue())
        self.assertIn("formal_eligible=false", stdout.getvalue())
        self.assertIn("product_decision_eligible=false", stdout.getvalue())
        document, _receipt = load_committed_json_artifact(
            self.observer_receipt_path,
            expected_kind=(
                "android-external-observer-synthetic-verification"
            ),
        )
        self.assertFalse(document["observer_execution_verified"])
        self.assertFalse(document["formal_eligible"])
        self.assertFalse(document["product_decision_eligible"])

    def test_release_attestation_and_source_commitment_tampering_fails_closed(
        self,
    ) -> None:
        cases = [
            (("run_id",), "run_ffffffffffff"),
            (("known_release", "package_name"), "other.package"),
            (("known_release", "version_code"), 18),
            (("known_release", "apk_sha256"), "a" * 64),
            (
                ("known_release", "app_signing_cert_sha256"),
                "a" * 64,
            ),
            (("known_release", "runner_build_sha256"), "a" * 64),
            (("observer_challenge_sha256",), "a" * 64),
            (
                (
                    "attestation_observation",
                    "certificate_chain_verified",
                ),
                False,
            ),
            (
                ("attestation_observation", "locked_verified_boot"),
                False,
            ),
            (
                (
                    "attestation_observation",
                    "attestation_envelope_raw_sha256",
                ),
                "a" * 64,
            ),
            (
                (
                    "source_receipt_commitments",
                    "engineering_receipt_commit_sha256",
                ),
                "a" * 64,
            ),
            (("android_commitments", "pcm_set_sha256"), "a" * 64),
            (("device_identity_commitment_sha256",), "a" * 64),
        ]
        for path, replacement in cases:
            with self.subTest(path=path):
                attack = copy.deepcopy(self.observer_evidence)
                target = attack
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = replacement
                self._write_evidence(attack)
                with self.assertRaises(ContractError):
                    self._verify()
                self.assertFalse(self.observer_receipt_path.exists())

    def test_two_clean_process_and_independent_measurement_attacks_fail(
        self,
    ) -> None:
        cases: list[tuple[tuple[object, ...], object]] = [
            (("repetitions",), [self._repetition(1)]),
            (
                ("repetitions", 1, "process_instance_sha256"),
                self.observer_evidence["repetitions"][0][
                    "process_instance_sha256"
                ],
            ),
            (("repetitions", 0, "clean_process_start"), False),
            (
                ("repetitions", 0, "preexisting_process_detected"),
                True,
            ),
            (("repetitions", 0, "process_exit_observed"), False),
            (("repetitions", 0, "oom_kill_count"), 1),
            (("repetitions", 0, "crash_count"), 1),
            (
                ("repetitions", 0, "host_pcm_set_sha256"),
                "a" * 64,
            ),
            (
                ("repetitions", 0, "host_artifact_set_sha256"),
                "a" * 64,
            ),
            (
                ("repetitions", 0, "host_accuracy_output_sha256"),
                "a" * 64,
            ),
            (
                (
                    "repetitions",
                    0,
                    "host_pcm_measurements",
                    0,
                    "pcm_payload_sha256",
                ),
                "a" * 64,
            ),
            (
                (
                    "repetitions",
                    0,
                    "host_artifact_measurements",
                    0,
                    "sha256",
                ),
                "a" * 64,
            ),
            (
                (
                    "repetitions",
                    0,
                    "external_clip_timings",
                    0,
                    "stop_to_final_ns",
                ),
                1_000_000_000,
            ),
        ]
        for path, replacement in cases:
            with self.subTest(path=path):
                attack = copy.deepcopy(self.observer_evidence)
                target = attack
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = replacement
                self._write_evidence(attack)
                with self.assertRaises(ContractError):
                    self._verify()
                self.assertFalse(self.observer_receipt_path.exists())

    def test_external_latency_must_meet_both_absolute_and_relative_tolerance(
        self,
    ) -> None:
        attack = copy.deepcopy(self.observer_evidence)
        timing = attack["repetitions"][0]["external_clip_timings"][0]
        runner_latency_ns = self.engineering["clip_measurements"][0][
            "stop_to_final_ns"
        ]
        timing["stop_to_final_ns"] = runner_latency_ns + 6_000_000
        timing["monotonic_final_ns"] = (
            timing["monotonic_stop_ns"] + timing["stop_to_final_ns"]
        )
        self._write_evidence(attack)

        with self.assertRaisesRegex(ContractError, "frozen tolerance"):
            self._verify()
        self.assertFalse(self.observer_receipt_path.exists())

    def test_host_timeline_clock_domain_and_round_separation_fail_closed(
        self,
    ) -> None:
        first = self.observer_evidence["repetitions"][0]
        second = self.observer_evidence["repetitions"][1]
        cases = [
            (
                "clip_stop_before_round",
                lambda attack: attack["repetitions"][0][
                    "external_clip_timings"
                ][0].update(
                    {
                        "monotonic_stop_ns": (
                            first["host_monotonic_start_ns"] - 100_000_000
                        ),
                        "monotonic_final_ns": (
                            first["host_monotonic_start_ns"]
                        ),
                        "stop_to_final_ns": 100_000_000,
                    }
                ),
            ),
            (
                "clip_final_after_round",
                lambda attack: attack["repetitions"][0][
                    "external_clip_timings"
                ][0].update(
                    {
                        "monotonic_stop_ns": (
                            first["host_monotonic_end_ns"]
                        ),
                        "monotonic_final_ns": (
                            first["host_monotonic_end_ns"]
                            + 100_000_000
                        ),
                        "stop_to_final_ns": 100_000_000,
                    }
                ),
            ),
            (
                "overlapping_rounds",
                lambda attack: attack["repetitions"][1].update(
                    {
                        "host_monotonic_start_ns": (
                            first["host_monotonic_end_ns"]
                        )
                    }
                ),
            ),
            (
                "reused_timeline",
                lambda attack: attack["repetitions"][1].update(
                    {
                        "host_monotonic_start_ns": (
                            first["host_monotonic_start_ns"]
                        ),
                        "host_monotonic_end_ns": (
                            first["host_monotonic_end_ns"]
                        ),
                        "external_clip_timings": copy.deepcopy(
                            first["external_clip_timings"]
                        ),
                        "external_memory_samples": copy.deepcopy(
                            first["external_memory_samples"]
                        ),
                    }
                ),
            ),
            (
                "cross_clock_domain",
                lambda attack: attack["repetitions"][1].update(
                    {"host_clock_boot_id_sha256": "a" * 64}
                ),
            ),
            (
                "wrong_clock_source",
                lambda attack: attack["host_clock"].update(
                    {"source": "android_elapsed_realtime_nanos"}
                ),
            ),
        ]
        self.assertLess(
            first["host_monotonic_end_ns"],
            second["host_monotonic_start_ns"],
        )
        for name, mutate in cases:
            with self.subTest(name=name):
                attack = copy.deepcopy(self.observer_evidence)
                mutate(attack)
                self._write_evidence(attack)
                with self.assertRaises(ContractError):
                    self._verify()
                self.assertFalse(self.observer_receipt_path.exists())

    def test_external_memory_samples_derive_peaks_and_obey_host_limits(
        self,
    ) -> None:
        cases = [
            (
                "empty_samples",
                lambda attack: attack["repetitions"][0].update(
                    {"external_memory_samples": []}
                ),
            ),
            (
                "rss_peak_not_derived",
                lambda attack: attack["repetitions"][0].update(
                    {
                        "observed_peak_rss_bytes": (
                            attack["repetitions"][0][
                                "observed_peak_rss_bytes"
                            ]
                            + 1
                        )
                    }
                ),
            ),
            (
                "pss_peak_not_derived",
                lambda attack: attack["repetitions"][0].update(
                    {
                        "observed_peak_pss_bytes": (
                            attack["repetitions"][0][
                                "observed_peak_pss_bytes"
                            ]
                            + 1
                        )
                    }
                ),
            ),
            (
                "sample_pss_exceeds_rss",
                lambda attack: attack["repetitions"][0][
                    "external_memory_samples"
                ][0].update({"pss_bytes": 10, "rss_bytes": 9}),
            ),
            (
                "sample_exceeds_physical_ram",
                lambda attack: attack["repetitions"][0][
                    "external_memory_samples"
                ][1].update(
                    {
                        "rss_bytes": DEVICE_PHYSICAL_RAM_BYTES + 1,
                        "pss_bytes": DEVICE_PHYSICAL_RAM_BYTES,
                    }
                ),
            ),
            (
                "sample_exceeds_frozen_process_limit",
                lambda attack: attack["repetitions"][0][
                    "external_memory_samples"
                ][1].update(
                    {
                        "rss_bytes": MAX_PROCESS_MEMORY_BYTES + 1,
                        "pss_bytes": MAX_PROCESS_MEMORY_BYTES,
                    }
                ),
            ),
            (
                "absurd_sample",
                lambda attack: attack["repetitions"][0][
                    "external_memory_samples"
                ][1].update(
                    {"rss_bytes": 2**60, "pss_bytes": 2**59}
                ),
            ),
            (
                "sample_outside_round",
                lambda attack: attack["repetitions"][0][
                    "external_memory_samples"
                ][0].update(
                    {
                        "monotonic_ns": (
                            attack["repetitions"][0][
                                "host_monotonic_start_ns"
                            ]
                            - 1
                        )
                    }
                ),
            ),
            (
                "sample_order_reversed",
                lambda attack: attack["repetitions"][0][
                    "external_memory_samples"
                ][1].update(
                    {
                        "monotonic_ns": attack["repetitions"][0][
                            "external_memory_samples"
                        ][0]["monotonic_ns"]
                    }
                ),
            ),
            (
                "device_ram_policy_tamper",
                lambda attack: attack["host_resource_policy"].update(
                    {
                        "device_physical_ram_bytes": (
                            DEVICE_PHYSICAL_RAM_BYTES + 1
                        )
                    }
                ),
            ),
        ]
        for name, mutate in cases:
            with self.subTest(name=name):
                attack = copy.deepcopy(self.observer_evidence)
                mutate(attack)
                self._write_evidence(attack)
                with self.assertRaises(ContractError):
                    self._verify()
                self.assertFalse(self.observer_receipt_path.exists())

    def test_role_leakage_duplicate_json_and_reserved_kind_are_rejected(
        self,
    ) -> None:
        leaked = copy.deepcopy(self.observer_evidence)
        leaked["model_id"] = "secret-model"
        self._write_evidence(leaked)
        with self.assertRaisesRegex(ContractError, "field|leak|model"):
            self._verify()

        self.observer_evidence_path.write_text(
            '{"schema_version":"1.0","schema_version":"1.0"}',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ContractError, "duplicate"):
            self._verify()

        with self.assertRaisesRegex(
            ContractError,
            "reserved Android artifact kind",
        ):
            commit_json_artifact(
                self.observer_receipt_path,
                {"run_id": android_fixture.RUN_ID},
                artifact_kind=(
                    "android-external-observer-synthetic-verification"
                ),
                run_id=android_fixture.RUN_ID,
                evidence_sha256=android_fixture.SHA_A,
                plan_sha256=android_fixture.SHA_B,
                validate_evidence=lambda: None,
            )

    def test_observer_contract_schemas_are_strict_and_ineligible(self) -> None:
        contract_root = (
            Path(__file__).resolve().parents[1] / "contracts"
        )
        for filename in (
            "external-observer-evidence.schema.json",
            "external-observer-verification.schema.json",
        ):
            with self.subTest(filename=filename):
                schema = json.loads(
                    (contract_root / filename).read_text(encoding="utf-8")
                )
                self.assertEqual(
                    schema["$schema"],
                    "https://json-schema.org/draft/2020-12/schema",
                )
                self.assertEqual(schema["type"], "object")
                self.assertFalse(schema["additionalProperties"])
        verification_schema = json.loads(
            (
                contract_root
                / "external-observer-verification.schema.json"
            ).read_text(encoding="utf-8")
        )
        for field in (
            "formal_eligible",
            "product_decision_eligible",
            "adversarial_same_uid_resistant",
        ):
            self.assertEqual(
                verification_schema["properties"][field],
                {"const": False},
            )


if __name__ == "__main__":
    unittest.main()
