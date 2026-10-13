import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parents[1]))


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class SourceVerificationTest(unittest.TestCase):
    def setUp(self):
        self.guest = load("guest")
        self.pinned = self.guest.SOURCES["ffmpeg"]["signing_key_fingerprint"]
        self.key = self.guest.ROOT / self.guest.SOURCES["ffmpeg"]["signing_key"]

    def gpgv(self, stdout, returncode=0):
        run = patch.object(self.guest.subprocess, "run").start()
        self.addCleanup(patch.stopall)
        run.return_value.stdout, run.return_value.stderr = stdout, ""
        run.return_value.returncode = returncode
        return run

    def test_rejects_modified_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / "source.tar.xz"
            archive.write_bytes(b"modified source")
            with self.assertRaises(ValueError):
                self.guest.require_pin(archive, sha256(b"official source"))

    def test_rejects_signature_from_another_key(self):
        self.gpgv("[GNUPG:] GOODSIG 0 x\n[GNUPG:] VALIDSIG " + "A" * 40 + " 2026-01-01 1 0 4 0 1 8 00 "
                  + "B" * 40 + "\n")
        with self.assertRaises(ValueError):
            self.guest.verify_signature(Path("archive"), Path("signature"), self.key, self.pinned)

    def test_missing_valid_signature_fails(self):
        self.gpgv("[GNUPG:] BADSIG unknown\n", returncode=1)
        with self.assertRaises(ValueError):
            self.guest.verify_signature(Path("archive"), Path("signature"), self.key, self.pinned)

    def test_accepts_pinned_primary_signing_key(self):
        run = self.gpgv("[GNUPG:] GOODSIG 0 x\n[GNUPG:] VALIDSIG " + "A" * 40
                        + " 2026-01-01 1 0 4 0 1 8 00 " + self.pinned + "\n")
        self.guest.verify_signature(Path("archive"), Path("signature"), self.key, self.pinned)
        self.assertEqual("gpgv", run.call_args.args[0][0])

    def test_rejects_changed_license_text(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / "lib-1.tar.gz"
            with tarfile.open(archive, "w:gz") as tar:
                info = tarfile.TarInfo("lib-1/LICENSE")
                info.size = 7
                tar.addfile(info, io.BytesIO(b"changed"))
            with self.assertRaises(ValueError):
                self.guest.require_license(archive, "lib-1/LICENSE", sha256(b"bundled"))

class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.package = load("package")

    def helpers(self, out, guest_sha256):
        for platform in self.package.PLATFORMS:
            folder = out / "sandbox" / platform
            folder.mkdir(parents=True)
            hashes = {}
            for name in self.package.SHIPPED[platform.split("-")[0]]:
                data = (platform + name).encode()
                (folder / name).write_bytes(data)
                hashes[name] = sha256(data)
            (out / platform).mkdir()
            (out / platform / "validation.json").write_text(json.dumps(
                {"platform": platform, "guest_sha256": guest_sha256, "executables": hashes}))

    def test_packages_every_platform(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            self.helpers(out, "guest")
            found = self.package.platforms(out, "guest", require_all=True)
            self.assertEqual(sorted(self.package.PLATFORMS), sorted(found))
            self.assertEqual(8, sum(len(files) for files in found.values()))

    def test_missing_platform_fails_for_a_release(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            self.helpers(out, "guest")
            for path in sorted((out / "sandbox/macos-x86_64").iterdir()):
                path.unlink()
            (out / "sandbox/macos-x86_64").rmdir()
            with self.assertRaises(ValueError):
                self.package.platforms(out, "guest", require_all=True)
            self.assertEqual(5, len(self.package.platforms(out, "guest", require_all=False)))

    def test_changed_helper_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            self.helpers(out, "guest")
            (out / "sandbox/windows-x86_64/sandbox.exe").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                self.package.platforms(out, "guest", require_all=True)

    def test_helper_built_from_another_guest_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            self.helpers(out, "other guest")
            with self.assertRaises(ValueError):
                self.package.platforms(out, "guest", require_all=True)


if __name__ == "__main__":
    unittest.main()
