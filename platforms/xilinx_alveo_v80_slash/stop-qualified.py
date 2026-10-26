#!/usr/bin/env python3
# See LICENSE for license details.
"""Stop one recorded runtime controller without matching unrelated process names."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from deployment import require, sha


def open_pidfd(pid):
    if hasattr(os, "pidfd_open"):
        return os.pidfd_open(pid)
    libc = ctypes.CDLL(None, use_errno=True)
    require(
        hasattr(libc, "pidfd_open"),
        "Python or libc pidfd support is required for safe stops",
    )
    function = libc.pidfd_open
    function.argtypes = (ctypes.c_int, ctypes.c_uint)
    function.restype = ctypes.c_int
    descriptor = function(pid, 0)
    if descriptor < 0:
        raise OSError(ctypes.get_errno(), "pidfd_open")
    return descriptor


def send_pidfd_signal(descriptor, signum):
    if hasattr(signal, "pidfd_send_signal"):
        return signal.pidfd_send_signal(descriptor, signum)
    libc = ctypes.CDLL(None, use_errno=True)
    require(
        hasattr(libc, "pidfd_send_signal"), "Python or libc pidfd signaling is required"
    )
    function = libc.pidfd_send_signal
    function.argtypes = (ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint)
    function.restype = ctypes.c_int
    if function(descriptor, signum, None, 0) != 0:
        raise OSError(ctypes.get_errno(), "pidfd_send_signal")


def process_start(pid, proc=Path("/proc")):
    # comm may contain spaces or ')'; field 22 follows the final closing ')'.
    return (proc / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()[19]


def verify_owner(owner, bundle, proc=Path("/proc")):
    require(
        owner["bundle"] == str(bundle)
        and owner["deployment_manifest_sha256"] == sha(bundle / "deployment.json"),
        "Controller belongs to another deployment",
    )
    pid = owner["pid"]
    require(
        type(pid) is int and pid > 1 and pid != os.getpid(),
        "Invalid runtime controller PID",
    )
    require(
        process_start(pid, proc) == owner["start_ticks"], "Runtime PID has been reused"
    )
    command = (
        (proc / str(pid) / "cmdline").read_bytes().decode().rstrip("\0").split("\0")
    )
    require(
        str(bundle / "run-qualified.py") in command
        and "--bundle" in command
        and command[command.index("--bundle") + 1] == str(bundle),
        "Runtime process command differs",
    )
    return pid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--proof", type=Path, required=True)
    parser.add_argument("--host-config", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=30)
    args = parser.parse_args()
    require(args.timeout_seconds > 0, "Timeout must be positive")
    bundle = args.bundle.resolve(strict=True)
    if os.geteuid() != 0:
        config = json.loads(args.host_config.read_text())
        sudo = config.get("sudo", [])
        require(bool(sudo), "Configured privileged runner required")
        return subprocess.run(
            [*sudo, sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]],
            timeout=args.timeout_seconds + 15,
        ).returncode
    deadline = time.monotonic() + args.timeout_seconds
    owner_path = args.proof / "controller.json"
    while not owner_path.is_file():
        require(
            time.monotonic() < deadline,
            "Runtime controller did not create its ownership record",
        )
        time.sleep(min(0.2, deadline - time.monotonic()))
    owner = json.loads(owner_path.read_text())
    require(owner["state"] in ("running", "exited"), "Invalid controller state")
    if owner["state"] == "exited":
        require(
            owner["cleanup_completed"] is True,
            "Previous controller cleanup did not finish",
        )
        print("Controller already exited")
        return 0
    try:
        pid = verify_owner(owner, bundle)
        descriptor = open_pidfd(pid)
    except (FileNotFoundError, ProcessLookupError):
        final = json.loads(owner_path.read_text())
        require(
            final["state"] == "exited" and final["cleanup_completed"] is True,
            "Controller disappeared without completed cleanup",
        )
        return 0
    try:
        # Recheck after opening the pidfd; signals then address that process,
        # even if it exits and its numeric PID is subsequently reused.
        verify_owner(owner, bundle)
        send_pidfd_signal(descriptor, signal.SIGTERM)
        ready, _, _ = select.select(
            [descriptor], [], [], max(0, deadline - time.monotonic())
        )
        require(
            ready,
            "Controller cleanup exceeded its deadline; inspect the board before retrying",
        )
    finally:
        os.close(descriptor)
    final = json.loads((args.proof / "controller.json").read_text())
    require(
        final["state"] == "exited" and final["cleanup_completed"] is True,
        "Controller exited without completing its cleanup record",
    )
    print("Stopped the recorded runtime controller and completed its cleanup")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
