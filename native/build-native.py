"""Build and verify one native platform on a matching runner.

Outputs build/native/PLATFORM/ (shippable helper files plus validation.json) and
build/native/PLATFORM-probes/ (test-only probes, never shipped). verify-committed.py then
checks the committed helpers with the same probes.
"""
import argparse, os, pathlib, platform, shutil, subprocess, sys

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("platform")
args = parser.parse_args()
targets = {
    "windows-x86_64": "x86_64-pc-windows-msvc",
    "windows-aarch64": "aarch64-pc-windows-msvc",
    "linux-x86_64": "x86_64-unknown-linux-musl",
    "linux-aarch64": "aarch64-unknown-linux-musl",
    "macos-x86_64": "x86_64-apple-darwin",
    "macos-aarch64": "aarch64-apple-darwin",
}
target = targets[args.platform]
host = {"Windows": "windows", "Linux": "linux", "Darwin": "macos"}[platform.system()]
arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}[
    platform.machine().lower()]
assert args.platform == host + "-" + arch, "Build and execute on matching native runner"
windows = host == "windows"
env = dict(os.environ)
if windows:
    env["RUSTFLAGS"] = "-C target-feature=+crt-static"
if host == "macos":
    env["MACOSX_DEPLOYMENT_TARGET"] = "13.0"
manifest = str(root / "native/decoder/Cargo.toml")


def run(*cmd):
    subprocess.run(list(cmd), cwd=root, env=env, check=True)


run("rustup", "target", "add", target)
run("cargo", "run", "--release", "--locked", "--manifest-path", manifest, "--example",
    "precompile", "--", args.platform)
run("cargo", "test", "--release", "--locked", "--manifest-path", manifest, "--all-targets")
run("cargo", "build", "--release", "--locked", "--manifest-path", manifest, "--target", target,
    "--bins")
target_dir = pathlib.Path(env.get("CARGO_TARGET_DIR", root / "native/decoder/target")) / target / "release"
out = root / "build/native" / args.platform
probes = root / "build/native" / (args.platform + "-probes")
for directory in (out, probes):
    shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir(parents=True)
suffix = ".exe" if windows else ""
exe = "seqimages-decoder" + suffix
shutil.copy2(target_dir / exe, out / exe)
shutil.copy2(root / "native/wasm" / (args.platform + ".cwasm"), out / "decoder.cwasm")
if windows:
    vswhere = pathlib.Path(os.environ["ProgramFiles(x86)"]) / "Microsoft Visual Studio/Installer/vswhere.exe"
    install = subprocess.check_output(
        [str(vswhere), "-latest", "-products", "*", "-property", "installationPath"],
        text=True).strip()
    dev = pathlib.Path(install) / "Common7/Tools/VsDevCmd.bat"
    # A broker variant that stops once its suspended child exists, for cancellation tests.
    paused = probes / "cancellation-broker.cpp"
    source = (root / "native/sandbox.cpp").read_text()
    anchor = "  Handle process(pi.hProcess),thread(pi.hThread);"
    assert source.count(anchor) == 1
    paused.write_text(source.replace(
        anchor,
        anchor + '\n  std::cout<<"SUSPENDED_CHILD "<<pi.dwProcessId<<"\\n"<<std::flush;Sleep(INFINITE);'))
    arch_arg = "arm64" if arch == "aarch64" else "x64"
    flags = "/nologo /std:c++17 /O2 /MT /EHsc /W4"
    link = "/link /DYNAMICBASE /NXCOMPAT /HIGHENTROPYVA"
    lines = ["@echo off", f'call "{dev}" -arch={arch_arg} -host_arch={arch_arg}',
             "if errorlevel 1 exit /b 1"]
    for source_file, output in (
            (root / "native/sandbox.cpp", out / "sandbox.exe"),
            (root / "native/windows-probe.cpp", probes / "sandbox-probe.exe"),
            (paused, probes / "cancellation-broker.exe")):
        lines += [f'cl {flags} "{source_file}" /Fe:"{output}" /Fo:"{probes / (output.stem + ".obj")}" {link}',
                  "if errorlevel 1 exit /b 1"]
    script = probes / "build-broker.cmd"
    script.write_text("\n".join(lines) + "\n")
    run("cmd", "/c", str(script))
    for leftover in (*probes.glob("*.obj"), script, paused):
        leftover.unlink()
else:
    shutil.copy2(target_dir / "sandbox-probe", probes / "sandbox-probe")
if host == "macos":
    run("codesign", "--force", "--sign", "-", str(out / exe))

run(sys.executable, str(root / "native/verify-native.py"), args.platform, str(out), str(probes),
    str(out / "validation.json"))
