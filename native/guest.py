"""Build the WebAssembly decoder guest from the pinned upstream sources in native/sources.json.

usage: guest.py [--sources-only]

Downloads the FFmpeg and zlib archives into native/out/sources and stops on any mismatch with their
SHA-256 pins, FFmpeg's signature by the pinned key, or the bundled license texts. Then it builds
native/out/guest/decoder.wasm with the pinned wasi-sdk (or WASI_SDK_PATH) and records the inputs in
guest.json. --sources-only downloads and checks the sources without building.
"""
import argparse, base64, hashlib, json, os, pathlib, platform, shutil, subprocess, sys
import tarfile, tempfile, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = json.loads((ROOT / "native/sources.json").read_text())
ARCHIVES = ROOT / "native/out/sources"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def require_pin(path, expected):
    actual = sha256(path)
    if actual != expected:
        raise ValueError(f"{path.name}: SHA-256 {actual} does not match the pin {expected}")


def download(url, destination):
    with urllib.request.urlopen(url, timeout=60) as response, open(destination, "wb") as out:
        shutil.copyfileobj(response, out)


def dearmor(armored):
    """Binary OpenPGP data from an ASCII-armored block, as gpgv needs for its keyring."""
    lines = armored.strip().splitlines()
    start = next(i for i, line in enumerate(lines) if not line.strip()) + 1
    body = [line for line in lines[start:-1] if line and not line.startswith("=")]
    return base64.b64decode("".join(body))


def verify_signature(archive, signature, key, fingerprint):
    """Raises unless gpgv reports a good signature by the pinned primary key fingerprint."""
    with tempfile.TemporaryDirectory(prefix="seqimages-gpgv-") as home:
        keyring = pathlib.Path(home) / "pinned.gpg"
        keyring.write_bytes(dearmor(key.read_text()))
        result = subprocess.run(
            ["gpgv", "--homedir", home, "--status-fd", "1", "--keyring", str(keyring),
             str(signature), str(archive)],
            capture_output=True, text=True, check=False)
    status = [line.split() for line in result.stdout.splitlines()]
    good = any(fields[1:2] == ["GOODSIG"] for fields in status)
    signers = [fields[-1] for fields in status if fields[1:2] == ["VALIDSIG"]]
    if result.returncode != 0 or not good or signers != [fingerprint]:
        raise ValueError(f"{archive.name}: signature not verified by {fingerprint}\n"
                         f"{result.stdout}{result.stderr}")


def require_license(archive, member, expected):
    """Raises unless the archive's license file is the text the JAR bundles."""
    with tarfile.open(archive) as tar:
        data = tar.extractfile(member).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"{member} differs from the bundled notice; update native/notices.py "
                         "inputs and native/sources.json together")


def fetch_sources(directory=ARCHIVES):
    """Downloads and authenticates the FFmpeg and zlib archives; returns their paths."""
    ffmpeg, zlib = SOURCES["ffmpeg"], SOURCES["zlib"]
    shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir(parents=True)
    paths = {}
    for name, source in (("ffmpeg", ffmpeg), ("zlib", zlib)):
        path = directory / source["url"].rsplit("/", 1)[1]
        download(source["url"], path)
        require_pin(path, source["sha256"])
        for member, digest in source["license_files"].items():
            require_license(path, f"{name}-{source['version']}/{member}", digest)
        paths[name] = path
    signature = directory / ffmpeg["signature_url"].rsplit("/", 1)[1]
    download(ffmpeg["signature_url"], signature)
    verify_signature(paths["ffmpeg"], signature, ROOT / ffmpeg["signing_key"],
                     ffmpeg["signing_key_fingerprint"])
    print(f"FFmpeg {ffmpeg['version']} signed by {ffmpeg['signing_key_fingerprint']}; "
          f"zlib {zlib['version']} matches its pin")
    return paths


def wasi_sdk(cache):
    pinned = SOURCES["wasi_sdk"]
    installed = os.environ.get("WASI_SDK_PATH")
    if installed:
        version = (pathlib.Path(installed) / "VERSION").read_text().split()[0]
        if version != pinned["version"]:
            raise ValueError(f"WASI_SDK_PATH has wasi-sdk {version}; the guest pins {pinned['version']}")
        return pathlib.Path(installed), None
    arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "arm64", "aarch64": "arm64"}.get(
        platform.machine().lower())
    system = {"Linux": "linux", "Darwin": "macos"}.get(platform.system())
    asset = pinned["hosts"].get(f"{arch}-{system}")
    if asset is None:
        raise ValueError(f"no pinned wasi-sdk for {platform.machine()} {platform.system()}; "
                         "build the guest on Linux or macOS, or set WASI_SDK_PATH")
    directory = cache / f"wasi-sdk-{pinned['version']}-{arch}-{system}"
    if not (directory / "VERSION").is_file():
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / asset["url"].rsplit("/", 1)[1]
        if not archive.is_file() or sha256(archive) != asset["sha256"]:
            download(asset["url"], archive)
            require_pin(archive, asset["sha256"])
        shutil.rmtree(directory, ignore_errors=True)
        with tarfile.open(archive) as tar, tempfile.TemporaryDirectory(dir=cache) as unpack:
            tar.extractall(unpack, filter="data")
            (top,) = pathlib.Path(unpack).iterdir()
            top.rename(directory)
    return directory, asset["sha256"]


def build(out):
    archives = fetch_sources()
    sdk, sdk_sha256 = wasi_sdk(ROOT / "native/out/toolchains")
    out.mkdir(parents=True, exist_ok=True)
    wasm = out / "decoder.wasm"
    subprocess.run(["bash", str(ROOT / "native/build-wasm.sh"), str(wasm), str(archives["ffmpeg"]),
                    str(archives["zlib"])], cwd=ROOT, check=True,
                   env={**os.environ, "WASI_SDK_PATH": str(sdk)})
    ffmpeg, zlib = SOURCES["ffmpeg"], SOURCES["zlib"]
    record = {
        "decoder_wasm": {"bytes": wasm.stat().st_size, "sha256": sha256(wasm)},
        "ffmpeg": {"version": ffmpeg["version"], "sha256": ffmpeg["sha256"],
                   "signed_by": ffmpeg["signing_key_fingerprint"]},
        "zlib": {"version": zlib["version"], "sha256": zlib["sha256"]},
        "wasi_sdk": {"version": SOURCES["wasi_sdk"]["version"], "archive_sha256": sdk_sha256},
        "recipe": {name: sha256(ROOT / name)
                   for name in ("native/build-wasm.sh", "native/wasm/decoder.c")},
    }
    (out / "guest.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.sources_only:
            fetch_sources()
        else:
            build(ROOT / "native/out/guest")
    except ValueError as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
