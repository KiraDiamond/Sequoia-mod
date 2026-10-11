"""Check broker cancellation against processes, AppContainer profiles, file ACLs and leases.

usage: windows-cancellation.py BROKER PAUSED_BROKER HELPER

PAUSED_BROKER is the broker built to stop after creating its suspended child.
"""
import ctypes
import os
import pathlib
import struct
import subprocess
import sys
import tempfile
import time
import winreg
from ctypes import wintypes

kernel = ctypes.WinDLL("kernel32", use_last_error=True)
kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel.WaitForSingleObject.restype = wintypes.DWORD
kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
kernel.CloseHandle.argtypes = [wintypes.HANDLE]
kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
SYNCHRONIZE, TERMINATE = 0x00100000, 0x0001


class ProcessEntry(ctypes.Structure):
    _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
                ("th32ProcessID", wintypes.DWORD), ("th32DefaultHeapID", ctypes.c_size_t),
                ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
                ("th32ParentProcessID", wintypes.DWORD), ("pcPriClassBase", ctypes.c_long),
                ("dwFlags", wintypes.DWORD), ("szExeFile", ctypes.c_wchar * 260)]


kernel.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessEntry)]
kernel.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessEntry)]
broker, paused_broker, helper = (pathlib.Path(value).resolve() for value in sys.argv[1:4])
leases = pathlib.Path(os.environ["LOCALAPPDATA"]) / "SequoiaImages" / "leases"
packages = pathlib.Path(os.environ["LOCALAPPDATA"]) / "Packages"
MAPPINGS = r"Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppContainer\Mappings"
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010802000000907753de0000000c49444154"
    "789c63f8cff01f00040001ff220a3af00000000049454e44ae426082")


def children(parent):
    snapshot = kernel.CreateToolhelp32Snapshot(0x2, 0)
    entry, found = ProcessEntry(), []
    entry.dwSize = ctypes.sizeof(entry)
    try:
        ok = kernel.Process32FirstW(snapshot, ctypes.byref(entry))
        while ok:
            if entry.th32ParentProcessID == parent:
                found.append(entry.th32ProcessID)
            ok = kernel.Process32NextW(snapshot, ctypes.byref(entry))
    finally:
        kernel.CloseHandle(snapshot)
    return found


def profiles():
    names = set()
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, MAPPINGS) as mappings:
            for index in range(winreg.QueryInfoKey(mappings)[0]):
                with winreg.OpenKey(mappings, winreg.EnumKey(mappings, index)) as mapping:
                    try:
                        moniker = winreg.QueryValueEx(mapping, "Moniker")[0]
                    except OSError:
                        continue
                    if moniker.startswith("SequoiaImages."):
                        names.add(moniker)
    except FileNotFoundError:
        pass
    folders = {p.name for p in packages.glob("SequoiaImages.*")} if packages.exists() else set()
    return names | folders


def app_container_aces(path):
    """Explicit ACL entries for AppContainer SIDs (S-1-15-2-...) on one file."""
    script = ("(Get-Acl -LiteralPath $env:SEQIMAGES_ACL_PATH).GetAccessRules($true,$false,"
              "[System.Security.Principal.SecurityIdentifier]) | "
              "ForEach-Object { $_.IdentityReference.Value }")
    result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                            env={**os.environ, "SEQIMAGES_ACL_PATH": str(path)},
                            capture_output=True, text=True, timeout=60)
    assert result.returncode == 0, ("Get-Acl failed", result.stderr)
    return sorted(s for s in result.stdout.split() if s.startswith("S-1-15-2-"))


def state():
    return {"profiles": profiles(), "aces": app_container_aces(helper),
            "leases": sorted(p.name for p in leases.glob("*.lease")) if leases.exists() else []}


def wait_dead(pid, millis=5000):
    handle = kernel.OpenProcess(SYNCHRONIZE | TERMINATE, False, pid)
    if not handle:
        return True
    try:
        if kernel.WaitForSingleObject(handle, millis) == 0:
            return True
        kernel.TerminateProcess(handle, 1)
        kernel.WaitForSingleObject(handle, 3000)
        return False
    finally:
        kernel.CloseHandle(handle)


def start(executable, scratch):
    return subprocess.Popen([str(executable), "60", str(scratch), "1", str(helper), str(helper)],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def wait_for_child(process):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        found = children(process.pid)
        if found:
            return found[0]
        assert process.poll() is None, process.communicate(timeout=5)
        time.sleep(0.05)
    raise AssertionError("broker never started its decoder")


baseline = state()
print("BASELINE", {key: len(value) for key, value in baseline.items()})

for attempt in range(5):
    with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
        scratch = pathlib.Path(temp)
        process = start(broker, scratch)
        child = wait_for_child(process)
        (scratch / "cancel").write_bytes(b"")
        process.communicate(timeout=10)
        assert process.returncode == 3, ("cancel exit code", process.returncode)
        assert wait_dead(child), "cancelled broker left its decoder running"
        assert state() == baseline, ("graceful cancel left state", state(), baseline)
print("GRACEFUL_CANCEL_OK: 5 cancels; decoder exited; profiles, ACEs and leases restored")

leaked = 0
for attempt in range(3):
    with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
        process = start(paused_broker, pathlib.Path(temp))
        line = process.stdout.readline().decode().strip()
        assert line.startswith("SUSPENDED_CHILD "), (line, process.poll())
        child = int(line.split()[1])
        process.kill()
        process.communicate(timeout=10)
        assert wait_dead(child), "killing the broker orphaned its suspended decoder"
        leaked += 1
for attempt in range(2):
    with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
        process = start(broker, pathlib.Path(temp))
        child = wait_for_child(process)
        process.kill()
        process.communicate(timeout=10)
        assert wait_dead(child), "killing the broker orphaned its running decoder"
        leaked += 1
after_kills = state()
assert len(after_kills["leases"]) == len(baseline["leases"]) + leaked, after_kills
print(f"HARD_KILL_OK: {leaked} killed brokers; decoders exited; {leaked} leases recorded the leftover state")

with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
    process = start(broker, pathlib.Path(temp))
    out, err = process.communicate(struct.pack(">I", len(PNG)) + PNG, timeout=60)
    assert process.returncode == 0 and out, (process.returncode, err.decode(errors="replace"))
assert state() == baseline, ("next broker did not remove leftover state", state(), baseline)
print("RECOVERY_OK: next decode revoked leftover grants, deleted profiles and cleared leases")
