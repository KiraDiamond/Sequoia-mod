"""Fetch pinned upstream releases. No cached or vendored archive is accepted."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

FFMPEG_SIGNER = "FCF986EA15E6E293A5644F10B4322F04D67658D8"
# FFmpeg's signer: https://ffmpeg.org/download.html#release_9.0
# zlib's SHA-256: https://zlib.net/
# SDK digest: https://github.com/WebAssembly/wasi-sdk/releases/tag/wasi-sdk-34
SOURCES = {
    "ffmpeg-9.0.2.tar.xz": (
        "https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz",
        "8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"),
    "zlib-1.3.2.tar.gz": (
        "https://zlib.net/fossils/zlib-1.3.2.tar.gz",
        "bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16"),
}
SDK = ("wasi-sdk-34.0-x86_64-linux.tar.gz",
       "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/wasi-sdk-34.0-x86_64-linux.tar.gz",
       "b761e3a0721dbae9c09a0059e5fdb2bf917d1b4a8a7b430fb3b5aafb0984b2c4")


def download(url, destination):
    with urllib.request.urlopen(url, timeout=60) as response:
        if not response.url.startswith("https://"):
            raise ValueError("Non-HTTPS download redirect")
        with destination.open("wb") as output:
            shutil.copyfileobj(response, output)


def verify_hash(path, expected):
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"{path.name}: SHA-256 mismatch: {actual}")


def gpg(home, *args):
    return subprocess.run(
        ["gpg", "--no-options", "--homedir", home.as_posix(), "--batch",
         "--no-auto-key-retrieve", *[a.as_posix() if isinstance(a, Path) else str(a) for a in args]],
        check=True, capture_output=True, text=True)


def verify_signature(archive, signature, home):
    result = gpg(home, "--status-fd", "1", "--verify", signature, archive)
    signatures = [line.split() for line in result.stdout.splitlines()
                  if line.startswith("[GNUPG:] VALIDSIG ")]
    if not any(FFMPEG_SIGNER in (s[2], s[-1]) for s in signatures):
        raise ValueError("FFmpeg signature is not from the pinned upstream signing key")


def fetch(destination, sources_only=False):
    destination.mkdir(parents=True, exist_ok=True)
    sources = dict(SOURCES)
    if not sources_only:
        sources[SDK[0]] = SDK[1:]
    for filename, (url, digest) in sources.items():
        path = destination / filename
        download(url, path)
        verify_hash(path, digest)
        print(f"Verified SHA-256: {filename}")
    archive = destination / "ffmpeg-9.0.2.tar.xz"
    signature = destination / (archive.name + ".asc")
    download(SOURCES[archive.name][0] + ".asc", signature)
    with tempfile.TemporaryDirectory(prefix="ffmpeg-gpg-") as temp:
        home = Path(temp)
        key = home / "upstream.asc"
        download("https://ffmpeg.org/ffmpeg-devel.asc", key)
        gpg(home, "--import", key)
        verify_signature(archive, signature, home)
    print(f"Verified FFmpeg release signature: {FFMPEG_SIGNER}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources-only", action="store_true", help="Do not download the Linux WASI SDK")
    args = parser.parse_args()
    fetch(Path(__file__).resolve().parents[1] / "build/native-downloads", args.sources_only)
