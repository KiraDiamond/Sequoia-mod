"""Check the verified helpers against the guest and write the provenance and corresponding source.

usage: package.py [--require-all]

Every helper in native/out/sandbox/PLATFORM/ must match the files its native/out/PLATFORM/
validation.json recorded and have been verified with native/out/guest/decoder.wasm. Writes
native/out/sandbox/provenance.json, which the JAR ships, and the reproducible
native/out/dist/sequoia-images-native-source.zip: the authenticated FFmpeg and zlib archives with
FFmpeg's signature, the build recipe and helper sources, and the notices. It is a zip because
Modrinth accepts zip files next to the JAR. --require-all fails unless all six platforms are present.
"""
import argparse, hashlib, json, os, pathlib, subprocess, sys, zipfile

import guest

ROOT = guest.ROOT
OUT = ROOT / "native/out"
PLATFORMS = ["windows-x86_64", "windows-aarch64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"]
SHIPPED = {"windows": ["sandbox.exe", "seqimages-decoder.exe"], "linux": ["seqimages-decoder"],
           "macos": ["seqimages-decoder"]}
# Repository files a recipient needs to rebuild the guest and the helpers.
SOURCE_FILES = ["LICENSE.txt", ".github/workflows/build.yml", "native", "src/main/resources/licenses"]
SOURCE_EXCLUDES = ("native/out", "native/decoder/target")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def platforms(out, guest_sha256, require_all):
    """Hashes of each present platform's shipped files; raises ValueError on any mismatch."""
    found = {}
    for name in PLATFORMS:
        if not (out / "sandbox" / name).is_dir():
            continue
        files = {f: sha256((out / "sandbox" / name / f).read_bytes()) for f in SHIPPED[name.split("-")[0]]}
        validation = json.loads((out / name / "validation.json").read_text())
        if validation != {"platform": name, "guest_sha256": guest_sha256, "executables": files}:
            raise ValueError(f"{name}: the helper differs from the one verified with this guest")
        found[name] = files
    missing = [name for name in PLATFORMS if name not in found]
    if not found or (missing and require_all):
        raise ValueError("missing platforms: " + ", ".join(missing))
    return found


def source_archive(path):
    """A reproducible zip, so equal sources give equal bytes."""
    upstream = [guest.SOURCES[key][field] for key, field in
                (("ffmpeg", "url"), ("zlib", "url"), ("ffmpeg", "signature_url"))]
    members = [(f"upstream/{url.rsplit('/', 1)[1]}", guest.ARCHIVES / url.rsplit("/", 1)[1]) for url in upstream]
    for entry in SOURCE_FILES:
        base = ROOT / entry
        for file in [base] if base.is_file() else sorted(p for p in base.rglob("*") if p.is_file()):
            relative = file.relative_to(ROOT).as_posix()
            if not relative.startswith(SOURCE_EXCLUDES) and "__pycache__" not in relative:
                members.append((relative, file))
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for relative, file in sorted(members):
            info = zipfile.ZipInfo("sequoia-images-native-source/" + relative, (1980, 1, 1, 0, 0, 0))
            info.create_system, info.external_attr = 3, 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, file.read_bytes())
    return len(members)


def package(require_all):
    record = json.loads((OUT / "guest/guest.json").read_text())
    if guest.sha256(OUT / "guest/decoder.wasm") != record["decoder_wasm"]["sha256"]:
        raise ValueError("guest.json does not describe native/out/guest/decoder.wasm")
    for source in (guest.SOURCES["ffmpeg"], guest.SOURCES["zlib"]):
        guest.require_pin(guest.ARCHIVES / source["url"].rsplit("/", 1)[1], source["sha256"])
    found = platforms(OUT, record["decoder_wasm"]["sha256"], require_all)
    archive = OUT / "dist/sequoia-images-native-source.zip"
    count = source_archive(archive)
    commit = os.environ.get("GITHUB_SHA") or subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    run = (f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/"
           f"{os.environ['GITHUB_RUN_ID']}" if "GITHUB_RUN_ID" in os.environ else None)
    provenance = {
        "source_commit": commit,
        "ci_run": run,
        "guest": record,
        "platforms": found,
        "corresponding_source": {"file": archive.name, "sha256": guest.sha256(archive)},
    }
    (OUT / "sandbox/provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"{len(found)} platform(s) from guest {record['decoder_wasm']['sha256'][:12]}; "
          f"source archive {count} files")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-all", action="store_true")
    try:
        package(parser.parse_args().require_all)
    except ValueError as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
