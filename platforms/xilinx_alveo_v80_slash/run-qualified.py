#!/usr/bin/env python3
# See LICENSE for license details.
"""Run an exact SLASH deployment under one board lock and an overall deadline."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import time
import tty

sys.dont_write_bytecode = True
from deployment import (
    read_pcie_links,
    require,
    sha,
    verify_bundle,
    verify_device_audit,
    verify_pcie_links,
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument(
        "--host-config",
        type=Path,
        default=Path.home() / ".config/firesim/v80-runtime.json",
    )
    parser.add_argument("--bdf", required=True)
    parser.add_argument("--driver", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument(
        "--acceptance-test",
        action="store_true",
        help="Explicitly test an offline-qualified candidate",
    )
    parser.add_argument(
        "--expect", help="Required native output regular expression for acceptance"
    )
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--log-dir", type=Path)
    parser.add_argument("driver_args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    require(args.timeout_seconds > 0, "Timeout must be positive")
    require(
        re.fullmatch(
            r"[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}(?:\.[0-7])?", args.bdf
        ),
        "Invalid BDF",
    )
    root = args.bundle.resolve(strict=True)
    manifest = verify_bundle(root, accepted=not args.acceptance_test)
    driver = (args.driver or root / "qualified-driver").resolve(strict=True)
    require(
        sha(driver) == manifest["driver_sha256"],
        "Native driver differs from the bundled binary",
    )
    require(
        not args.acceptance_test or (args.expect and not manifest["board_validated"]),
        "Acceptance requires an unpromoted candidate and --expect",
    )
    expected = re.compile(args.expect) if args.expect else None
    if args.verify_only:
        print(
            "PASS: exact image, driver, register map, reports and runtime scripts verified"
        )
        return 0
    header = (root / "FireSim-generated.const.h").read_text()
    mapping = re.search(r"CLOCKBRIDGEMODULE_struct\{([^}]+)\}", header)
    require(mapping, "Generated ClockBridge register map missing")
    addresses = dict(re.findall(r"\.(\w+)\s*=\s*(\d+)", mapping[1]))
    require(
        all(
            int(addresses[k]) >= 0x40 for k in ("hCycle_0", "hCycle_1", "hCycle_latch")
        ),
        "ClockBridge overlaps the kernel control aperture",
    )
    forwarded = []
    source = (
        args.driver_args[1:] if args.driver_args[:1] == ["--"] else args.driver_args
    )
    for arg in source:
        if arg.startswith("+binary_file="):
            require(
                Path(arg.split("=", 1)[1]).resolve() == root / "firesim.vbin",
                "Requested a different vbin",
            )
        elif arg.startswith("+bdf="):
            require(arg.split("=", 1)[1] == args.bdf, "Requested a different card")
        elif arg != "+v80-use-loaded-image":
            forwarded.append(arg)
    position = (
        forwarded.index("+permissive-off")
        if "+permissive-off" in forwarded
        else len(forwarded)
    )
    forwarded[position:position] = [
        "+binary_file=" + str(root / "firesim.vbin"),
        "+bdf=" + args.bdf,
        "+v80-use-loaded-image",
    ]
    config_path = args.host_config.resolve(strict=True)
    config = json.loads(config_path.read_text())
    require(config["bdf"] == args.bdf, "Host configuration belongs to another card")
    # One privileged controller owns the lock, child groups and their cleanup.
    # Do not place unkillable privileged children under an unprivileged timer.
    if os.geteuid() != 0:
        sudo = config.get("sudo", [])
        require(
            bool(sudo),
            "Configure a privileged controller for the board lock and process guard",
        )
        command = [
            *sudo,
            "env",
            "PATH=" + os.environ.get("PATH", ""),
            sys.executable,
            str(Path(__file__).resolve()),
            "--host-config",
            str(config_path),
            *sys.argv[1:],
        ]
        return subprocess.run(
            ["timeout", "-k", "15", str(args.timeout_seconds + 15), *command],
            timeout=args.timeout_seconds + 35,
        ).returncode
    deadline = time.monotonic() + args.timeout_seconds
    logdir = (
        args.log_dir or Path.cwd() / ("v80-runtime-proof-" + str(time.time_ns()))
    ).resolve()
    logdir.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    if config.get("library_path"):
        env["LD_LIBRARY_PATH"] = config["library_path"]
    result = dict(
        state="running",
        hardware_tested=False,
        functional_pass=False,
        exit_code=1,
        deployment_manifest_sha256=sha(root / "deployment.json"),
        checks={},
        logs=[],
        bdf=args.bdf,
        timeout_seconds=args.timeout_seconds,
        stage="lock",
    )
    (logdir / "deployment-used.json").write_text(json.dumps(manifest, indent=2) + "\n")
    started = time.monotonic()
    server = None
    lock = None
    lock_owned = False
    descriptors = []

    def remaining(limit):
        seconds = min(limit, deadline - time.monotonic())
        require(seconds > 0, "Overall runtime deadline expired")
        return seconds

    def stop(process):
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)

    def run(name, command, limit=60, stdin=None):
        result["stage"] = name
        command = list(map(str, command))
        (logdir / (name + "-command.json")).write_text(
            json.dumps(command, indent=2) + "\n"
        )
        seconds = remaining(limit)
        with (logdir / (name + ".stdout.log")).open("w") as out, (
            logdir / (name + ".stderr.log")
        ).open("w") as err:
            process = subprocess.Popen(
                command,
                env=env,
                stdout=out,
                stderr=err,
                stdin=stdin,
                start_new_session=True,
            )
            try:
                code = process.wait(timeout=seconds)
            except subprocess.TimeoutExpired:
                stop(process)
                raise TimeoutError(name + " exceeded its bounded deadline")
            except BaseException:
                stop(process)
                raise
            require(code == 0, f"{name} failed with exit {code}")
        return (logdir / (name + ".stdout.log")).read_text(errors="replace") + (
            logdir / (name + ".stderr.log")
        ).read_text(errors="replace")

    def pcie_health(name):
        result["stage"] = name
        snapshot = read_pcie_links(args.bdf)
        (logdir / (name + ".json")).write_text(json.dumps(snapshot, indent=2) + "\n")
        verify_pcie_links(snapshot)
        result["checks"][name.replace("-", "_")] = True

    def health(name):
        pcie_health(name.replace("health-", "pcie-"))
        text = run(name, [config["v80_smi"], "list"])
        require(
            args.bdf in text
            and re.search(r"PF0: OK.*PF1: OK.*PF2: OK.*VRTD: OK", text),
            "Card health check failed",
        )

    stop_requested = False

    def interrupted(signum, frame):
        nonlocal stop_requested
        stop_requested = True
        # Let a bounded JTAG programming transaction finish before honoring a
        # normal stop request. Its own deadline still applies on a real stall.
        if result["stage"] != "program":
            raise InterruptedError("Runtime controller interrupted")

    owner = dict(
        pid=os.getpid(),
        start_ticks=Path("/proc/self/stat").read_text().rsplit(")", 1)[1].split()[19],
        bundle=str(root),
        deployment_manifest_sha256=sha(root / "deployment.json"),
        state="running",
    )

    def save_owner():
        temporary = logdir / "controller.json.tmp"
        temporary.write_text(json.dumps(owner, indent=2) + "\n")
        temporary.replace(logdir / "controller.json")

    save_owner()
    previous_term = signal.signal(signal.SIGTERM, interrupted)
    try:
        libraries_text = run("runtime-libraries", ["ldd", driver])
        require(
            "not found" not in libraries_text,
            "Native runtime has unresolved shared libraries",
        )
        libraries = {
            name: sha(Path(path))
            for name, path in re.findall(
                r"^\s*(\S+)\s+=>\s+(/[^\n]+?)\s+\(0x[0-9a-fA-F]+\)",
                libraries_text,
                re.M,
            )
        }
        require(
            any(name.startswith("libvrt.so") for name in libraries)
            and any(name.startswith("libvrtdpp.so") for name in libraries),
            "SLASH runtime libraries missing",
        )
        result["runtime_libraries"] = libraries
        if manifest["board_validated"]:
            require(
                libraries == manifest["runtime_libraries"],
                "Runtime libraries differ from board acceptance",
            )
        result["checks"]["runtime_libraries"] = True
        result["stage"] = "lock"
        lock_path = Path(config["board_lock"])
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        lock = lock_path.open("a+")
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                lock_owned = True
                break
            except BlockingIOError:
                time.sleep(min(0.2, remaining(1)))
        # The bundled guard recognizes this new driver name as well as old ports.
        run("guard-before", [sys.executable, root / "guard-board.py"])
        result["checks"]["guard_before"] = True
        health("health-before")
        result["checks"]["health_before"] = True
        for port in (3121, 3042):
            with socket.socket() as probe:
                probe.settimeout(1)
                require(
                    probe.connect_ex(("127.0.0.1", port)) != 0,
                    "Unexpected hardware server is already running",
                )
        server_bin = config.get(
            "jtag_hw_server", str(Path(config["vivado"]).with_name("hw_server"))
        )
        with (logdir / "jtag-server.log").open("w") as server_log:
            server = subprocess.Popen(
                [server_bin, "-s", "tcp:127.0.0.1:3121"],
                cwd=logdir,
                env=env,
                stdout=server_log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        ready_deadline = time.monotonic() + remaining(45)
        while True:
            require(server.poll() is None, "Owned hardware server exited")
            with socket.socket() as probe:
                probe.settimeout(1)
                if probe.connect_ex(("127.0.0.1", 3121)) == 0:
                    break
            require(
                time.monotonic() < ready_deadline,
                "Owned hardware server did not become ready",
            )
            time.sleep(0.2)
        result["hardware_tested"] = True

        def device_audit(name, programmed):
            status_path = logdir / (name + "-registers.txt")
            run(
                name + "-registers",
                [
                    config["vivado"],
                    "-mode",
                    "batch",
                    "-source",
                    root / "read-device-status.tcl",
                    "-tclargs",
                    status_path,
                ],
                600,
            )
            # Keep raw buffers at the log root so the existing acceptance
            # inventory binds every byte without leaving unindexed subfolders.
            run(
                name + "-plm",
                [
                    config.get("xsdb", str(Path(config["vivado"]).with_name("xsdb"))),
                    root / "read-plm-status.tcl",
                    logdir,
                ],
                240,
            )
            buffers = []
            for slr in range(3):
                source = logdir / f"slr{slr}.bin"
                target = logdir / f"{name}-slr{slr}.bin"
                source.rename(target)
                buffers.append(target.read_bytes())
            audit = verify_device_audit(status_path.read_text(), buffers, programmed)
            (logdir / (name + "-audit.json")).write_text(
                json.dumps(audit, indent=2) + "\n"
            )
            result["checks"][name.replace("-", "_")] = True
            return buffers

        before_buffers = device_audit("device-before", False)
        run(
            "program",
            [
                config["vivado"],
                "-mode",
                "batch",
                "-source",
                root / "program-reviewed-partial-jtag.tcl",
                "-tclargs",
                root / "partial.pdi",
            ],
            900,
        )
        result["checks"]["program"] = True
        if stop_requested:
            raise InterruptedError("Stopped after completing JTAG programming")
        programmed_buffers = device_audit("device-programmed", True)
        require(
            all(
                before != after
                for before, after in zip(before_buffers, programmed_buffers)
            ),
            "A programmed SLR PMC buffer did not change from the pre-program audit",
        )
        pcie_health("pcie-programmed")
        frequency = str(manifest["clock_hz"])
        run(
            "clock-set",
            [config["clk_force"], args.bdf, root / "firesim.vbin", frequency],
        )
        text = run(
            "clock-read", [config["clk_monitor"], args.bdf, root / "firesim.vbin"]
        )
        require(text.splitlines()[-1] == frequency, "Runtime clock readback differs")
        result["checks"]["clock_readback"] = True
        run(
            "clock-measure",
            [
                config["clock_probe"],
                root / "firesim.vbin",
                addresses["hCycle_0"],
                addresses["hCycle_1"],
                addresses["hCycle_latch"],
                frequency,
            ],
        )
        result["checks"]["clock_measure"] = True
        pcie_health("pcie-native")
        stdin = None
        if not sys.stdin.isatty():
            master, slave = os.openpty()
            descriptors.extend((master, slave))
            tty.setraw(slave)
            (logdir / "console-in").symlink_to(os.ttyname(slave))
            stdin = master
        output = run(
            "native-driver", [driver, *forwarded], args.timeout_seconds, stdin=stdin
        )
        result["checks"]["native_driver"] = True
        require(
            expected is None or expected.search(output),
            "Required functional output was absent",
        )
        result["checks"]["functional_output"] = True
        run("guard-after", [sys.executable, root / "guard-board.py"])
        result["checks"]["guard_after"] = True
        health("health-after")
        result["checks"]["health_after"] = True
        device_audit("device-after", True)
        result.update(state="completed", functional_pass=True, exit_code=0)
        return 0
    except BaseException as error:
        result.update(state="failed", error=str(error))
        raise
    finally:
        cleanup_errors = []
        if lock_owned:
            # A failed BAR transaction must not trigger more register accesses.
            # Preserve sysfs-only link evidence even on timeout/interruption.
            try:
                snapshot = read_pcie_links(args.bdf)
                (logdir / "pcie-final.json").write_text(
                    json.dumps(snapshot, indent=2) + "\n"
                )
            except BaseException as error:
                cleanup_errors.append("PCIe final evidence: " + str(error))
        try:
            if server is not None:
                stop(server)
        except BaseException as error:
            cleanup_errors.append(str(error))
        for descriptor in descriptors:
            os.close(descriptor)
        if lock is not None:
            lock.close()
        signal.signal(signal.SIGTERM, previous_term)
        if cleanup_errors:
            result.update(
                state="failed",
                functional_pass=False,
                exit_code=1,
                cleanup_errors=cleanup_errors,
            )
        owner.update(
            state="exited",
            exit_code=result["exit_code"],
            cleanup_completed=not cleanup_errors,
        )
        save_owner()
        result["elapsed_seconds"] = time.monotonic() - started
        result["logs"] = [
            dict(path=p.name, sha256=sha(p))
            for p in sorted(logdir.iterdir())
            if p.is_file() and not p.is_symlink() and p.name != "board-acceptance.json"
        ]
        (logdir / "board-acceptance.json").write_text(
            json.dumps(result, indent=2) + "\n"
        )
        print(
            json.dumps(
                dict(
                    log_dir=str(logdir),
                    **{k: v for k, v in result.items() if k != "logs"},
                )
            ),
            flush=True,
        )
        if cleanup_errors:
            raise RuntimeError(
                "Owned hardware server did not terminate within the cleanup deadline"
            )


if __name__ == "__main__":
    raise SystemExit(main())
