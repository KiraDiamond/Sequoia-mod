"""Verify the committed helpers for one platform with the probes build-native.py produced.

usage: verify-committed.py PLATFORM

Writes build/native/PLATFORM/committed-validation.json and committed-comparison.json. Equal
hashes only show that this run produced identical files; they are not a reproducibility claim.
"""
import hashlib, json, pathlib, shutil, stat, subprocess, sys, tempfile

root = pathlib.Path(__file__).resolve().parents[1]
name = sys.argv[1]
out = root / "build/native" / name
probes = root / "build/native" / (name + "-probes")
committed = root / "src/main/resources/sandbox" / name
# Git keeps resources non-executable; the mod sets the execute bit when it extracts them.
with tempfile.TemporaryDirectory(prefix="seqimages-committed-") as temp:
    extracted = pathlib.Path(temp)
    for file in committed.iterdir():
        target = extracted / file.name
        shutil.copyfile(file, target)
        target.chmod(stat.S_IRWXU)
    subprocess.run([sys.executable, str(root / "native/verify-native.py"), name, str(extracted),
                    str(probes), str(out / "committed-validation.json")], check=True)
shipped = ["seqimages-decoder.exe", "sandbox.exe"] if name.startswith("windows") else ["seqimages-decoder"]
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
comparison = {}
for file in shipped:
    fresh, kept = digest(out / file), digest(committed / file)
    comparison[file] = {"fresh": fresh, "committed": kept, "identical": fresh == kept}
(out / "committed-comparison.json").write_text(json.dumps(comparison, indent=2) + "\n")
print(json.dumps(comparison, indent=2))
