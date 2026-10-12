"""Stage only the tested helper executables downloaded from this workflow run."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

HELPERS = {
    "windows-x86_64": ("seqimages-decoder.exe", "sandbox.exe"),
    "windows-aarch64": ("seqimages-decoder.exe", "sandbox.exe"),
    "linux-x86_64": ("seqimages-decoder",),
    "linux-aarch64": ("seqimages-decoder",),
    "macos-x86_64": ("seqimages-decoder",),
    "macos-aarch64": ("seqimages-decoder",),
}


def stage(artifacts, resources):
    files = []
    for platform, names in HELPERS.items():
        directory = artifacts / ("native-" + platform)
        report = json.loads((directory / "validation.json").read_text())
        if report["platform"] != platform:
            raise ValueError(f"Wrong validation platform: {platform}")
        for name in names:
            path = directory / name
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != report["executables"][name]:
                raise ValueError(f"Helper changed after validation: {platform}/{name}")
            files.append((path, resources / "sandbox" / platform / name))
    # Check every platform before copying anything; never package partial builds or probes.
    for source, target in files:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("resources", type=Path)
    args = parser.parse_args()
    stage(args.artifacts, args.resources)
