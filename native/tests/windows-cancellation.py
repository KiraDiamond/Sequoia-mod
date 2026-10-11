"""Check broker cancellation before a suspended child can be resumed."""
import ctypes
from ctypes import wintypes
import pathlib
import queue
import subprocess
import sys
import tempfile
import threading

kernel = ctypes.WinDLL("kernel32", use_last_error=True)
kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel.OpenProcess.restype = wintypes.HANDLE
kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
kernel.WaitForSingleObject.restype = wintypes.DWORD
kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
kernel.CloseHandle.argtypes = [wintypes.HANDLE]

broker, helper = map(lambda value: pathlib.Path(value).resolve(), sys.argv[1:3])
child = None
with tempfile.TemporaryDirectory(prefix="seqimages-cancel-test-") as temp:
    process = subprocess.Popen(
        [str(broker), "20", temp, "1", str(helper), str(helper)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    lines = queue.Queue()
    threading.Thread(target=lambda: lines.put(process.stdout.readline()),
                     daemon=True).start()
    try:
        line = lines.get(timeout=8).decode().strip()
        assert line.startswith("SUSPENDED_CHILD "), (line, process.poll())
        pid = int(line.split()[1])
        child = kernel.OpenProcess(0x00100000 | 0x0001, False, pid)
        assert child, ctypes.get_last_error()
        process.kill()
        process.wait(timeout=3)
        assert kernel.WaitForSingleObject(child, 3000) == 0, (
            "Cancelling the broker orphaned its suspended decoder")
        process.communicate(timeout=3)
        print("BROKER_CANCELLATION_OK: suspended child terminated; inherited pipes closed")
    finally:
        if child:
            # Also clean up after a failed regression test against the old broker.
            if kernel.WaitForSingleObject(child, 0) != 0:
                kernel.TerminateProcess(child, 1)
                kernel.WaitForSingleObject(child, 3000)
            kernel.CloseHandle(child)
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=3)
