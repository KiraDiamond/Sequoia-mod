"""Build, test and verify one platform's decoder helper on a matching runner.

usage: build-native.py PLATFORM [--guest GUEST.wasm] [--verify-only] [--target TRIPLE]

Precompiles native/out/guest/decoder.wasm for PLATFORM, embeds it in the helper and runs the Rust
tests. Then it runs the helper: exact pixels for the Klipy fixture, confined before any input
arrives, malformed input rejected, OS sandbox denials and, on Windows, broker cancellation and
cleanup. --verify-only repeats those checks on a helper restored from cache. --guest builds a helper
from another guest, such as a modified decoder, and skips the checks, which expect the pinned guest.
--target overrides the Rust target for a local build, for example x86_64-unknown-linux-gnu without
a musl C compiler.

  native/out/sandbox/PLATFORM/  the files the mod JAR ships
  native/out/PLATFORM/          decoder.cwasm, test probes and validation.json
"""
import argparse, hashlib, json, os, pathlib, platform, shutil, socket, struct, subprocess, sys
import tempfile, time
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
CRATE = ROOT / "native/decoder"
TARGETS = {
    "windows-x86_64": "x86_64-pc-windows-msvc",
    "windows-aarch64": "aarch64-pc-windows-msvc",
    "linux-x86_64": "x86_64-unknown-linux-musl",
    "linux-aarch64": "aarch64-unknown-linux-musl",
    "macos-x86_64": "x86_64-apple-darwin",
    "macos-aarch64": "aarch64-apple-darwin",
}
FIXTURE = ROOT / "src/test/resources/klipy-report.mp4"
# SHA-256 of the pixels the helper decodes from FIXTURE.
EXPECTED_PIXELS = "3566824334c607fe33015d853295d2695c9d399aef88d864f2e9a4d329033c2c"

parser = argparse.ArgumentParser()
parser.add_argument("platform", choices=TARGETS)
parser.add_argument("--guest", type=pathlib.Path)
parser.add_argument("--verify-only", action="store_true")
parser.add_argument("--target")
args = parser.parse_args()
host = {"Windows": "windows", "Linux": "linux", "Darwin": "macos"}[platform.system()]
arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}[platform.machine().lower()]
if args.platform != f"{host}-{arch}":
    sys.exit(f"build {args.platform} on a matching runner, not {host}-{arch}")
if not args.guest and not FIXTURE.exists():
    sys.exit(f"the helper checks need {FIXTURE.relative_to(ROOT)} from the repository; "
             "pass --guest to build without them")
windows = host == "windows"
target = args.target or TARGETS[args.platform]
out = ROOT / "native/out/sandbox" / args.platform
work = ROOT / "native/out" / args.platform
helper = out / ("seqimages-decoder.exe" if windows else "seqimages-decoder")
broker = out / "sandbox.exe"
guest = (args.guest or ROOT / "native/out/guest/decoder.wasm").resolve()
env = dict(os.environ, SEQIMAGES_AOT_PATH=str(work / "decoder.cwasm"))
if windows:
    env["RUSTFLAGS"] = "-C target-feature=+crt-static"
if host == "macos":
    env["MACOSX_DEPLOYMENT_TARGET"] = "13.0"


def run(*command, cwd=ROOT):
    subprocess.run(command, cwd=cwd, env=env, check=True)


def cargo(subcommand, *options):
    # Run in the crate so rustup selects its pinned toolchain.
    run("cargo", subcommand, "--release", "--locked", "--target", target, *options, cwd=CRATE)


def build():
    for directory in (out, work):
        shutil.rmtree(directory, ignore_errors=True)
        directory.mkdir(parents=True)
    run("rustup", "target", "add", target, cwd=CRATE)
    cargo("run", "--example", "precompile", "--", target, str(guest), str(work / "decoder.cwasm"))
    cargo("test", "--all-targets")
    cargo("build", "--bins")
    binaries = CRATE / "target" / target / "release"
    shutil.copy2(binaries / helper.name, helper)
    if windows:
        build_broker()
    else:
        shutil.copy2(binaries / "sandbox-probe", work / "sandbox-probe")
    if host == "macos":
        run("codesign", "--force", "--sign", "-", str(helper))


def build_broker():
    vswhere = pathlib.Path(os.environ["ProgramFiles(x86)"]) / "Microsoft Visual Studio/Installer/vswhere.exe"
    install = subprocess.check_output(
        [str(vswhere), "-latest", "-products", "*", "-property", "installationPath"], text=True).strip()
    vs_arch = "arm64" if arch == "aarch64" else "x64"
    lines = ["@echo off", f'call "{pathlib.Path(install) / "Common7/Tools/VsDevCmd.bat"}" -arch={vs_arch} '
             f"-host_arch={vs_arch}", "if errorlevel 1 exit /b 1"]
    for source, output, defines in (
            ("sandbox.cpp", broker, ""),
            ("sandbox.cpp", work / "cancellation-broker.exe", "/DSEQIMAGES_TEST_PAUSE"),
            ("windows-probe.cpp", work / "sandbox-probe.exe", "")):
        lines += [f'cl /nologo /std:c++17 /O2 /MT /EHsc /W4 {defines} "{ROOT / "native" / source}" '
                  f'/Fe:"{output}" /Fo:"{work / (output.stem + ".obj")}" /link /DYNAMICBASE /NXCOMPAT '
                  "/HIGHENTROPYVA", "if errorlevel 1 exit /b 1"]
    script = work / "build-broker.cmd"
    script.write_text("\n".join(lines) + "\n")
    run("cmd", "/c", str(script))
    for leftover in (*work.glob("*.obj"), script):
        leftover.unlink()


def decode(scratch, data, prepared=False):
    """Runs one decode; a prepared helper first sets up and sandboxes itself, then gets the input."""
    command = [str(broker), "60", str(scratch), "1", str(helper), str(helper)] if windows else [str(helper)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        if prepared:
            time.sleep(0.5)
            assert process.poll() is None, process.stderr.read().decode(errors="replace")
            check_confined(process.pid)
        out, err = process.communicate(struct.pack(">I", len(data)) + data, timeout=40)
        return process.returncode, out, err.decode(errors="replace")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)


def check_confined(pid):
    """The OS sandbox of a helper waiting for input, where observable from outside."""
    if sys.platform.startswith("linux"):
        lines = pathlib.Path(f"/proc/{pid}/status").read_text().splitlines()
        status = dict(line.split(":", 1) for line in lines if ":" in line)
        assert (status["Seccomp"].strip(), status["NoNewPrivs"].strip()) == ("2", "1"), "helper not confined"
    elif sys.platform == "darwin":
        import ctypes
        libsystem = ctypes.CDLL("/usr/lib/libSystem.B.dylib")
        libsystem.sandbox_check.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        assert libsystem.sandbox_check(pid, None, 0) == 1, "helper not under Seatbelt"


def check_denials(scratch):
    """The probe must be denied files, sockets and processes; only its own pre-sandbox control
    connection may reach the listener."""
    sentinel, denied = scratch / "sentinel", scratch / "denied"
    sentinel.write_bytes(b"marker")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(4)
        port = str(listener.getsockname()[1])
        if windows:
            probe = work / "sandbox-probe.exe"
            command = [str(broker), "10", str(scratch), "1", str(probe), str(probe), str(sentinel), str(denied), port]
            marker, controls = "APPCONTAINER_DENIALS_OK", 0
        else:
            command = [str(work / "sandbox-probe"), str(sentinel), str(denied), port]
            marker, controls = "OS_SANDBOX_DENIALS_OK", 1
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        assert result.returncode == 0 and marker in result.stdout, (result.stdout, result.stderr)
        assert not denied.exists()
        listener.settimeout(0.5)
        accepted = 0
        try:
            while True:
                listener.accept()[0].close()
                accepted += 1
        except socket.timeout:
            pass
        assert accepted == controls, ("sandboxed process reached the listener", accepted)


def verify():
    media = FIXTURE.read_bytes()
    with tempfile.TemporaryDirectory(prefix="seqimages-native-test-") as temp:
        scratch = pathlib.Path(temp).resolve()
        code, pixels, err = decode(scratch, media)
        assert code == 0, err
        assert hashlib.sha256(pixels).hexdigest() == EXPECTED_PIXELS, ("unexpected pixels", len(pixels))
        assert decode(scratch, media, prepared=True)[:2] == (0, pixels), "prepared helper decoded differently"
        # Fine one-pixel stripes must survive above the animation resolution, including alpha.
        def png_chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        row = bytes([0, 0, 0, 255, 255, 255, 255, 128]) * 384
        png = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">2I5B", 768, 2, 8, 6, 0, 0, 0))
               + png_chunk(b"IDAT", zlib.compress((b"\0" + row) * 2)) + png_chunk(b"IEND", b""))
        code, pixels, err = decode(scratch, png)
        assert code == 0, err
        assert struct.unpack(">4I", pixels[:16]) == (0x53494D31, 768, 2, 1)
        assert pixels[20:] == struct.pack(">2I", 0xff000000, 0x80ffffff) * 768
        code, pixels, err = decode(scratch, png + png)
        assert code != 0 and not pixels, "multiple still-image frames accepted"
        code, out, err = decode(scratch, b"bad")
        assert code != 0 and not out, (code, len(out))
        for name, dimensions in (("lossless", (2, 2, 1)), ("lossy", (8, 8, 1)), ("animated", (8, 8, 3)),
                                 ("detail", (768, 2, 1))):
            code, pixels, err = decode(scratch, (FIXTURE.parent / f"webp-{name}.webp").read_bytes())
            assert code == 0, (name, err)
            assert struct.unpack(">4I", pixels[:16]) == (0x53494D31, *dimensions), name
            if name == "lossless":
                assert pixels[20:] == struct.pack(">4I", 0xffff0000, 0x8000ff00, 0xff0000ff, 0), name
            if name == "detail":
                assert pixels[20:] == struct.pack(">2I", 0xff000000, 0x80ffffff) * 768, name
            if name == "animated":
                stride = 4 + 8 * 8 * 4
                assert [struct.unpack_from(">I", pixels, 16 + i * stride)[0] for i in range(3)] == [100, 200, 300]
        code, pixels, err = decode(scratch, (FIXTURE.parent / "webp-oversized.webp").read_bytes())
        assert code != 0 and not pixels, "oversized WebP accepted"
        check_denials(scratch)
    if windows:
        run(sys.executable, str(ROOT / "native/tests/windows-cancellation.py"), str(broker),
            str(work / "cancellation-broker.exe"), str(helper))


if not args.verify_only:
    build()
if args.guest:
    print(f"built {helper} from {guest}; the checks run only for the pinned guest")
    sys.exit()
verify()
validation = {
    "platform": args.platform,
    "guest_sha256": hashlib.sha256(guest.read_bytes()).hexdigest(),
    "executables": {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(out.iterdir())},
}
(work / "validation.json").write_text(json.dumps(validation, indent=2) + "\n")
print(json.dumps(validation, indent=2))
