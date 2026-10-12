"""Check broker cancellation and cleanup against processes, AppContainer profiles, file ACLs and leases.

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
kernel.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
kernel.CreateMutexW.restype = wintypes.HANDLE
kernel.ReleaseMutex.argtypes = [wintypes.HANDLE]
SYNCHRONIZE, TERMINATE = 0x00100000, 0x0001
WAIT_OBJECT_0 = 0


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


advapi = ctypes.WinDLL("advapi32", use_last_error=True)
advapi.GetNamedSecurityInfoW.argtypes = [
    wintypes.LPCWSTR, ctypes.c_int, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p),
    ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p),
    ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_void_p)]
advapi.GetNamedSecurityInfoW.restype = wintypes.DWORD
advapi.GetAce.argtypes = [ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p)]
advapi.GetAce.restype = wintypes.BOOL
advapi.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.LPWSTR)]
advapi.ConvertSidToStringSidW.restype = wintypes.BOOL
kernel.LocalFree.argtypes = [ctypes.c_void_p]


class AclHeader(ctypes.Structure):
    _fields_ = [("revision", ctypes.c_ubyte), ("sbz1", ctypes.c_ubyte), ("size", wintypes.WORD),
                ("ace_count", wintypes.WORD), ("sbz2", wintypes.WORD)]


class AceHeader(ctypes.Structure):
    _fields_ = [("type", ctypes.c_ubyte), ("flags", ctypes.c_ubyte), ("size", wintypes.WORD),
                ("mask", wintypes.DWORD)]


def app_container_aces(path):
    """Explicit allow/deny ACL entries for AppContainer SIDs (S-1-15-2-...) on one file."""
    dacl, descriptor = ctypes.c_void_p(), ctypes.c_void_p()
    error = advapi.GetNamedSecurityInfoW(str(path), 1, 4, None, None, ctypes.byref(dacl), None,
                                         ctypes.byref(descriptor))
    assert error == 0, ("GetNamedSecurityInfo failed", error)
    try:
        sids = []
        for index in range(AclHeader.from_address(dacl.value).ace_count):
            ace = ctypes.c_void_p()
            assert advapi.GetAce(dacl, index, ctypes.byref(ace)), ctypes.get_last_error()
            header = AceHeader.from_address(ace.value)
            # Access-allowed (0) and access-denied (1) entries that were not inherited (0x10).
            if header.type not in (0, 1) or header.flags & 0x10:
                continue
            text = wintypes.LPWSTR()
            assert advapi.ConvertSidToStringSidW(ace.value + ctypes.sizeof(AceHeader),
                                                 ctypes.byref(text)), ctypes.get_last_error()
            try:
                if text.value.startswith("S-1-15-2-"):
                    sids.append(text.value)
            finally:
                kernel.LocalFree(text)
        return sorted(sids)
    finally:
        kernel.LocalFree(descriptor)


def state():
    return {"profiles": profiles(), "aces": app_container_aces(helper),
            "leases": sorted(p.name for p in leases.glob("*.lease")) if leases.exists() else []}


def open_child(parent, pid):
    """A handle to the broker's decoder, opened while it is known to be that process."""
    handle = kernel.OpenProcess(SYNCHRONIZE | TERMINATE, False, pid)
    assert handle, ("cannot open the decoder", pid, ctypes.get_last_error())
    # Holding the handle pins the PID; check it still names the broker's child.
    if pid not in children(parent):
        kernel.CloseHandle(handle)
        raise AssertionError(("decoder exited before it could be checked", pid))
    return handle


def wait_dead(handle, millis=5000):
    """Waits for the process behind an open_child handle, killing it if it lingers; closes the handle."""
    try:
        if kernel.WaitForSingleObject(handle, millis) == WAIT_OBJECT_0:
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
            return open_child(process.pid, found[0])
        assert process.poll() is None, process.communicate(timeout=5)
        time.sleep(0.05)
    raise AssertionError("broker never started its decoder")


baseline = state()
print("BASELINE", {key: len(value) for key, value in baseline.items()})

for attempt in range(5):
    with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
        scratch = pathlib.Path(temp)
        process = start(broker, scratch)
        decoder = wait_for_child(process)
        (scratch / "cancel").write_bytes(b"")
        # Keep stdin open until the broker exits, so the decoder cannot end on EOF first.
        process.wait(timeout=10)
        process.communicate(timeout=10)
        assert process.returncode == 3, ("cancel exit code", process.returncode)
        assert wait_dead(decoder), "cancelled broker left its decoder running"
        assert state() == baseline, ("graceful cancel left state", state(), baseline)
print("GRACEFUL_CANCEL_OK: 5 cancels; decoder exited; profiles, ACEs and leases restored")

def outstanding():
    """Profiles, helper ACEs and leases beyond the baseline."""
    current = state()
    return tuple(len(current[key]) - len(baseline[key]) for key in ("profiles", "aces", "leases"))


# Every broker sweeps what brokers killed before it left behind, so after each kill exactly the
# latest killed broker's profile, file grant and lease remain.
kills = 0
for attempt in range(3):
    with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
        process = start(paused_broker, pathlib.Path(temp))
        line = process.stdout.readline().decode().strip()
        assert line.startswith("SUSPENDED_CHILD "), (line, process.poll())
        decoder = open_child(process.pid, int(line.split()[1]))
        process.kill()
        process.communicate(timeout=10)
        assert wait_dead(decoder), "killing the broker orphaned its suspended decoder"
        kills += 1
        assert outstanding() == (1, 1, 1), ("after kill", kills, outstanding())
for attempt in range(2):
    with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
        process = start(broker, pathlib.Path(temp))
        decoder = wait_for_child(process)
        process.kill()
        process.communicate(timeout=10)
        assert wait_dead(decoder), "killing the broker orphaned its running decoder"
        kills += 1
        assert outstanding() == (1, 1, 1), ("after kill", kills, outstanding())
print(f"HARD_KILL_OK: {kills} killed brokers; decoders exited; each next broker swept the previous leftovers")

# A finished decode must not wait long for a busy ACL mutex: the broker makes one short cleanup
# attempt, returns the decoder's result and leaves its own state to the lease.
leftover = set(state()["leases"])
with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
    process = start(broker, pathlib.Path(temp))
    # The startup sweep and file grants are done once the decoder exists.
    decoder = wait_for_child(process)
    mutex = kernel.CreateMutexW(None, False, "Local\\SequoiaImagesSandboxAclV1")
    assert mutex, ("ACL mutex", ctypes.get_last_error())
    try:
        assert kernel.WaitForSingleObject(mutex, 5000) in (0, 0x80), "could not hold the ACL mutex"
        started = time.monotonic()
        out, err = process.communicate(struct.pack(">I", len(PNG)) + PNG, timeout=60)
        elapsed = time.monotonic() - started
        message = err.decode(errors="replace")
        assert process.returncode == 0 and out, (process.returncode, message)
        assert "deferred" in message, ("cleanup did not defer while the mutex was held", message)
        assert elapsed < 8, ("finished decode waited for the busy mutex", elapsed)
        assert wait_dead(decoder), "decoder outlived its broker"
    finally:
        kernel.ReleaseMutex(mutex)
        kernel.CloseHandle(mutex)
own = set(state()["leases"]) - set(baseline["leases"])
assert len(own) == 1 and not own & leftover, ("deferred cleanup must leave only its own lease", own, leftover)
assert outstanding() == (1, 1, 1), ("deferred cleanup left", outstanding())
print(f"BUSY_CLEANUP_OK: decode returned in {elapsed:.1f} s with the ACL mutex held; its cleanup was left to the lease")

with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
    process = start(broker, pathlib.Path(temp))
    out, err = process.communicate(struct.pack(">I", len(PNG)) + PNG, timeout=60)
    assert process.returncode == 0 and out, (process.returncode, err.decode(errors="replace"))
assert state() == baseline, ("next broker did not remove leftover state", state(), baseline)
print("RECOVERY_OK: next decode revoked leftover grants, deleted profiles and cleared leases")
