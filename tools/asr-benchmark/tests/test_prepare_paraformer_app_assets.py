from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest

from kittyecho_asr_bench.contracts import ContractError


_SCRIPT_PATH = (
    Path(__file__).resolve().parents[1]
    / "scripts"
    / "prepare_paraformer_app_assets.py"
)
_SPEC = importlib.util.spec_from_file_location(
    "prepare_paraformer_app_assets",
    _SCRIPT_PATH,
)
assert _SPEC is not None and _SPEC.loader is not None
_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)


class PrepareParaformerAppAssetsTest(unittest.TestCase):
    def test_copy_rechecks_frozen_identity_bytes_and_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "model.int8.onnx"
            destination = root / "generated.onnx"
            payload = b"frozen-paraformer"
            source.write_bytes(payload)
            identity = source.stat()
            expected = SimpleNamespace(
                st_dev=identity.st_dev,
                st_ino=identity.st_ino,
                size_bytes=identity.st_size,
                mode=identity.st_mode & 0o7777,
                nlink=identity.st_nlink,
            )

            _MODULE._copy_verified(
                source=source,
                destination=destination,
                expected_bytes=len(payload),
                expected_sha256=hashlib.sha256(payload).hexdigest(),
                expected_identity=expected,
            )

            self.assertEqual(destination.read_bytes(), payload)
            self.assertEqual(destination.stat().st_nlink, 1)

    def test_external_inputs_reject_wrong_names_repository_files_and_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            external = root.parent / f"{root.name}-external"
            external.mkdir()
            self.addCleanup(lambda: external.rmdir())
            internal = root / "model.int8.onnx"
            internal.write_bytes(b"x")
            wrong = external / "wrong.onnx"
            wrong.write_bytes(b"x")
            link = external / "model.int8.onnx"
            link.symlink_to(internal)
            self.addCleanup(link.unlink)
            self.addCleanup(wrong.unlink)

            with self.assertRaises(ContractError):
                _MODULE._require_external_named_file(
                    internal,
                    root,
                    "model.int8.onnx",
                    "test artifact",
                )
            with self.assertRaises(ContractError):
                _MODULE._require_external_named_file(
                    wrong,
                    root,
                    "model.int8.onnx",
                    "test artifact",
                )
            with self.assertRaises(ContractError):
                _MODULE._require_external_named_file(
                    link,
                    root,
                    "model.int8.onnx",
                    "test artifact",
                )


if __name__ == "__main__":
    unittest.main()
