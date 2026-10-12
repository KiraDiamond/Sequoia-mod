"""Execute a helper build and verify exact pixels, input handling and OS sandbox denials.

usage: verify-native.py PLATFORM HELPER_DIR PROBE_DIR [REPORT]

HELPER_DIR holds the freshly built helper (and sandbox.exe on Windows). PROBE_DIR holds test-only probes from the same source: sandbox-probe on Unix,
sandbox-probe.exe and cancellation-broker.exe on Windows.
"""
import hashlib, json, os, pathlib, socket, struct, subprocess, sys, tempfile, time

root = pathlib.Path(__file__).resolve().parents[1]
name = sys.argv[1]
helper_dir = pathlib.Path(sys.argv[2]).resolve()
probe_dir = pathlib.Path(sys.argv[3]).resolve()
report_path = pathlib.Path(sys.argv[4]) if len(sys.argv) > 4 else helper_dir / "validation.json"
windows = name.startswith("windows")
helper = helper_dir / ("seqimages-decoder.exe" if windows else "seqimages-decoder")
broker = helper_dir / "sandbox.exe"
media = (root / "src/test/resources/klipy-report.mp4").read_bytes()
EXPECTED = "3566824334c607fe33015d853295d2695c9d399aef88d864f2e9a4d329033c2c"


def command(scratch):
    if windows:
        return [str(broker), "60", str(scratch), "1", str(helper), str(helper)]
    return [str(helper)]


def confinement(pid):
    """Reports the OS sandbox state of a helper waiting for input; None where not observable."""
    if sys.platform.startswith("linux"):
        status = dict(line.split(":", 1) for line in
                      pathlib.Path(f"/proc/{pid}/status").read_text().splitlines() if ":" in line)
        seccomp, no_new_privs = status["Seccomp"].strip(), status["NoNewPrivs"].strip()
        assert seccomp == "2" and no_new_privs == "1", ("helper not confined", seccomp, no_new_privs)
        return "seccomp filter and no_new_privs active before input"
    if sys.platform == "darwin":
        import ctypes
        libsystem = ctypes.CDLL("/usr/lib/libSystem.B.dylib")
        libsystem.sandbox_check.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        libsystem.sandbox_check.restype = ctypes.c_int
        assert libsystem.sandbox_check(pid, None, 0) == 1, "helper not under Seatbelt"
        return "Seatbelt active before input"
    return None


def decode(scratch, data, delay=0.0):
    """Runs one decode; with a delay the helper first sets up and sandboxes itself."""
    process = subprocess.Popen(command(scratch), stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        confined = None
        if delay:
            time.sleep(delay)
            assert process.poll() is None, ("helper exited while waiting for input",
                                            process.stderr.read().decode(errors="replace"))
            if not windows:
                confined = confinement(process.pid)
        out, err = process.communicate(struct.pack(">I", len(data)) + data, timeout=40)
        return process.returncode, out, err.decode(errors="replace"), confined
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)


report = {"platform": name, "helper_dir": str(helper_dir)}
with tempfile.TemporaryDirectory(prefix="seqimages-native-test-") as temp:
    scratch = pathlib.Path(temp).resolve()
    start = time.perf_counter()
    code, pixels, err, _ = decode(scratch, media)
    report["decode_seconds"] = time.perf_counter() - start
    assert code == 0, err
    digest = hashlib.sha256(pixels).hexdigest()
    assert digest == EXPECTED, (name, digest, len(pixels))
    report.update(pixel_sha256=digest, pixel_bytes=len(pixels), frames=44)

    code, delayed, err, confined = decode(scratch, media, delay=0.5)
    assert code == 0 and delayed == pixels, ("prepared helper decoded differently", code, err)
    report["prepared_before_input"] = True
    if confined:
        report["helper_confinement"] = confined

    code, out, err, _ = decode(scratch, b"bad")
    assert code != 0 and not out, (code, len(out))
    report["malformed_input_rejected"] = True

    sentinel = scratch / "sentinel"
    sentinel.write_bytes(b"marker")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(4)
        port = listener.getsockname()[1]
        if windows:
            probe = probe_dir / "sandbox-probe.exe"
            result = subprocess.run(
                [str(broker), "10", str(scratch), "1", str(probe), str(probe), str(sentinel),
                 str(scratch / "denied"), str(port)],
                capture_output=True, text=True, timeout=30)
            marker, control_connections = "APPCONTAINER_DENIALS_OK", 0
        else:
            result = subprocess.run(
                [str(probe_dir / "sandbox-probe"), str(sentinel), str(scratch / "denied"),
                 str(port)],
                capture_output=True, text=True, timeout=30)
            marker, control_connections = "OS_SANDBOX_DENIALS_OK", 1
        assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
        assert marker in result.stdout, result.stdout
        assert not (scratch / "denied").exists()
        listener.settimeout(0.5)
        accepted = 0
        try:
            while True:
                connection, _ = listener.accept()
                connection.close()
                accepted += 1
        except socket.timeout:
            pass
        # Only the probe's own pre-sandbox control connection may have arrived.
        assert accepted == control_connections, ("sandboxed process reached the listener", accepted)
        report["os_denials"] = result.stdout.strip()

    if windows:
        cancelled = subprocess.run(
            [sys.executable, str(root / "native/tests/windows-cancellation.py"),
             str(broker), str(probe_dir / "cancellation-broker.exe"), str(helper)],
            capture_output=True, text=True, timeout=240)
        assert cancelled.returncode == 0, (cancelled.stdout, cancelled.stderr)
        report["broker_cancellation"] = cancelled.stdout.strip().splitlines()

report["executables"] = {
    f.name: hashlib.sha256(f.read_bytes()).hexdigest()
    for f in sorted(helper_dir.iterdir()) if f.is_file() and f.suffix in ("", ".exe")
}
report_path.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
