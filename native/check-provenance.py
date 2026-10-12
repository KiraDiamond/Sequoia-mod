"""Check that bundled native artifacts match provenance.json and were built from current sources.

Every listed artifact must have the recorded size and SHA-256, and no build input may have
changed since native_build.source_commit, the commit the recorded CI run built. Matching hashes
identify the committed files; they do not show that a rebuild would reproduce them.
Needs full git history (actions/checkout with fetch-depth: 0).
"""
import hashlib, json, pathlib, subprocess, sys

root = pathlib.Path(__file__).resolve().parents[1]
provenance = json.loads((root / "native/provenance.json").read_text())
# Everything the native build reads. The .cwasm files are outputs, checked by hash instead.
INPUTS = [
    "native/decoder/Cargo.toml",
    "native/decoder/Cargo.lock",
    "native/decoder/src",
    "native/decoder/examples",
    "native/sandbox.cpp",
    "native/build-native.py",
    "native/build-wasm.sh",
    "native/wasm/decoder.c",
    "native/wasm/decoder.wasm",
    "native/vendor",
]
failures = []
for name, expected in provenance["artifacts"].items():
    path = root / name
    if not path.is_file():
        failures.append(f"{name}: missing")
        continue
    data = path.read_bytes()
    if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
        failures.append(f"{name}: differs from provenance.json")
source = provenance["native_build"]["source_commit"]
known = subprocess.run(["git", "cat-file", "-e", source + "^{commit}"], cwd=root)
if known.returncode != 0:
    failures.append(f"source commit {source} is not in this history")
else:
    changed = subprocess.run(["git", "diff", "--name-only", source, "HEAD", "--", *INPUTS],
                             cwd=root, capture_output=True, text=True, check=True).stdout.split()
    if changed:
        failures.append("native inputs changed after the recorded build: " + ", ".join(changed))
if failures:
    print("Bundled native artifacts are not current:\n  " + "\n  ".join(failures))
    sys.exit(1)
print(f"{len(provenance['artifacts'])} artifacts match provenance.json; inputs unchanged since {source}")
