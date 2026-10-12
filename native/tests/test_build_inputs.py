import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SourceVerificationTest(unittest.TestCase):
    def test_rejects_modified_archive(self):
        fetch = load("fetch-sources")
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / "source.tar.xz"
            archive.write_bytes(b"modified source")
            with self.assertRaises(ValueError):
                fetch.verify_hash(archive, hashlib.sha256(b"official source").hexdigest())

    def test_rejects_signature_from_another_key(self):
        fetch = load("fetch-sources")
        with patch.object(fetch.subprocess, "run") as run:
            run.return_value.stdout = "[GNUPG:] VALIDSIG " + "A" * 40 + " 2026-01-01 1 0 4 0 1 8 00\n"
            with self.assertRaises(ValueError):
                fetch.verify_signature(Path("archive"), Path("signature"), Path("keyring"))

    def test_missing_valid_signature_fails(self):
        fetch = load("fetch-sources")
        with patch.object(fetch.subprocess, "run") as run:
            run.return_value.stdout = "[GNUPG:] BADSIG unknown\n"
            with self.assertRaises(ValueError):
                fetch.verify_signature(Path("archive"), Path("signature"), Path("keyring"))

    def test_accepts_pinned_primary_signing_key(self):
        fetch = load("fetch-sources")
        with patch.object(fetch.subprocess, "run") as run:
            run.return_value.stdout = "[GNUPG:] VALIDSIG " + "A" * 40 + " 2026-01-01 1 0 4 0 1 8 00 " + fetch.FFMPEG_SIGNER + "\n"
            fetch.verify_signature(Path("archive"), Path("signature"), Path("keyring"))
            self.assertTrue(run.call_args.kwargs["check"])


class PackagingTest(unittest.TestCase):
    def test_no_committed_decoder_artifacts(self):
        root = Path(__file__).resolve().parents[2]
        tracked = subprocess.check_output(
            ["git", "ls-files", "native/vendor", "native/wasm", "src/main/resources/sandbox"],
            cwd=root, text=True).splitlines()
        self.assertEqual(["native/wasm/decoder.c"], tracked)

    def artifacts(self, root, stage):
        for platform, names in stage.HELPERS.items():
            folder = root / ("native-" + platform)
            folder.mkdir()
            hashes = {}
            for name in names:
                data = (platform + name).encode()
                (folder / name).write_bytes(data)
                hashes[name] = hashlib.sha256(data).hexdigest()
            (folder / "validation.json").write_text(json.dumps({"platform": platform, "executables": hashes}))
            (folder / "unshipped-probe.exe").write_bytes(b"test only")

    def test_packages_only_helpers_from_all_platforms(self):
        stage = load("stage-resources")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.artifacts(root, stage)
            stage.stage(root, root / "resources")
            files = list((root / "resources").rglob("*"))
            self.assertEqual(8, sum(p.is_file() for p in files))
            self.assertFalse(any(p.name == "unshipped-probe.exe" for p in files))

    def test_missing_platform_fails(self):
        stage = load("stage-resources")
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises((FileNotFoundError, ValueError)):
                stage.stage(Path(temp), Path(temp) / "resources")

    def test_changed_helper_fails(self):
        stage = load("stage-resources")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.artifacts(root, stage)
            (root / "native-windows-x86_64" / "sandbox.exe").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                stage.stage(root, root / "resources")
            self.assertFalse((root / "resources").exists())


if __name__ == "__main__":
    unittest.main()
