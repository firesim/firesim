#!/usr/bin/env python3
# See LICENSE for license details.
"""CPU-only deployment checks; every tool/board response below is predicted."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

PLATFORM = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PLATFORM))
import deployment as dep


def predicted_device():
    status, buffers = [], []
    for slr, error in enumerate((0, 2, 4)):
        status.append(f"REGISTER.ERROR_STATUS.SLR{slr}=0x{error:x}")
        for bit, value in (("DONE", 1), ("JCONFIG_ERROR", 0), ("JRDBK_ERROR", 0)):
            status.append(f"REGISTER.JTAG_STATUS.SLR{slr}.BIT[34]_{bit}={value}")
        data = bytearray(131072)
        text = b"Boot PDI Load: Started\nBoot PDI Load: Done\nSBI PDI Load: Started\nSBI PDI Load: Done\n"
        data[0x19000:0x19000 + len(text)] = text
        struct.pack_into("<QII", data, 0x14010, 0xF2019000, 0x4000, len(text))
        struct.pack_into("<III", data, 0x15800, *([0xDEADBEEF] * 3))
        buffers.append(bytes(data))
    return "\n".join(status), buffers


def load(name):
    spec = importlib.util.spec_from_file_location(name, PLATFORM / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def predicted_pcie():
    return {
        "0000:ab:00."
        + str(function): dict(
            vendor="0x10ee",
            device="0x50b" + str(4 + function),
            enable="1",
            current_link_speed="32.0 GT/s PCIe",
            current_link_width="8",
        )
        for function in range(3)
    }


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        parent = os.environ.get("SLASH_TEST_OUTPUT_DIR")
        if parent:
            self.root = Path(
                tempfile.mkdtemp(prefix=self._testMethodName + "-", dir=parent)
            )
        else:
            temp = tempfile.TemporaryDirectory(prefix="predicted slash board ")
            self.addCleanup(temp.cleanup)
            self.root = Path(temp.name)
        (self.root / "PREDICTED-NOT-HARDWARE.txt").write_text(
            "CPU fixtures only; no hardware was accessed.\n"
        )
        self.q = self.root / "qualified"
        self.q.mkdir()
        (self.q / "signoff").mkdir()
        (self.q / "signoff/partial.pdi").write_bytes(b"predicted partial")
        with tarfile.open(self.q / "firesim.vbin", "w:gz") as tar:
            data = b"predicted partial"
            entry = tarfile.TarInfo("images/partial.pdi")
            entry.size = len(data)
            tar.addfile(entry, io.BytesIO(data))
        self.header = self.q / "FireSim-generated.const.h"
        self.header.write_text(
            "CLOCKBRIDGEMODULE_struct{.hCycle_0 = 64, .hCycle_1 = 68, .hCycle_latch = 72}"
        )
        (self.q / "signoff/timing.txt").write_text(
            "Explicitly predicted passing Vivado report\n"
        )
        record = dict(
            schema_version=1,
            native_driver_board_validated=False,
            clock_hz=40000000,
            timing=dict(wns=0.1, whs=0.01, wpws=1.0, tns=0.0, ths=0.0, tpws=0.0),
            artifacts=[
                dict(path=p, sha256=dep.sha(self.q / p))
                for p in ("firesim.vbin", self.header.name)
            ],
            reports=[
                dict(
                    path="signoff/timing.txt",
                    sha256=dep.sha(self.q / "signoff/timing.txt"),
                )
            ],
        )
        (self.q / "build-qualification.json").write_text(json.dumps(record))
        (self.q / "result.json").write_text('{"exit_code": 0}')
        (self.q / "status.json").write_text(
            json.dumps(
                dict(
                    stage="offline-build-qualified",
                    state="completed",
                    hardware_tested=False,
                )
            )
        )
        self.driver = self.root / "predicted-native"
        self.driver.write_text("#!/bin/sh\nexit 0\n")
        self.driver.chmod(0o755)
        self.bundle = self.root / "candidate"
        self.export = load("export-deployment")
        self.calls = 0

    def candidate(self, output=None, metadata=None):
        out = output or self.bundle
        args = [
            "export-deployment.py",
            "candidate",
            "--qualified",
            str(self.q),
            "--driver",
            str(self.driver),
            "--header",
            str(self.header),
            "--metadata",
            (
                metadata
                if metadata is not None
                else "firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig-DefaultV80SlashConfig,firesim-commit:predicted"
            ),
            "--output",
            str(out),
        ]
        with patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()):
            self.export.main()
        return out

    def evidence(self):
        directory = self.root / "board"
        directory.mkdir()
        (directory / "native.stdout.log").write_text("PREDICTED PASS\n")
        evidence = dict(
            state="completed",
            hardware_tested=True,
            functional_pass=True,
            exit_code=0,
            deployment_manifest_sha256=dep.sha(self.bundle / "deployment.json"),
            checks={
                k: True
                for k in (
                    "guard_before",
                    "health_before",
                    "program",
                    "clock_readback",
                    "clock_measure",
                    "native_driver",
                    "functional_output",
                    "guard_after",
                    "health_after",
                    "runtime_libraries",
                    "device_before",
                    "device_programmed",
                    "device_after",
                    "pcie_before",
                    "pcie_programmed",
                    "pcie_native",
                    "pcie_after",
                )
            },
            runtime_libraries={"libvrt.so.1": "0" * 64, "libvrtdpp.so.1": "1" * 64},
            logs=[
                dict(
                    path="native.stdout.log",
                    sha256=dep.sha(directory / "native.stdout.log"),
                )
            ],
        )
        path = directory / "board-acceptance.json"
        path.write_text(json.dumps(evidence))
        return path, evidence

    def test_plm_ring_order_and_rejection(self):
        # A completed newest load crosses the physical end of the ring.
        done = b"[20.000]SBI PDI Load: Done\n"
        start = b"[10.000]SBI PDI Load: Started\n"
        logical = (b" " * (0x4000 - len(start) - len(done))) + start + done
        for offset in (0, 1, 7, len(done) - 4, 0x3FFF, 0x4000):
            with self.subTest(offset=offset):
                physical = logical[-offset:] + logical[:-offset] if offset else logical
                data = bytearray(131072)
                data[0x19000:0x1D000] = physical
                struct.pack_into("<QII", data, 0x14010, 0xF2019000, 0x4000, 0x80000000 | offset)
                self.assertEqual(dep.plm_debug_text(data), logical.decode())
        status, buffers = predicted_device()
        for offset in (1, 7, 0x3FFF):
            for complete in (True, False):
                with self.subTest(offset=offset, complete=complete):
                    tail = start + done if complete else done + start
                    logical = b" " * (0x4000 - len(tail)) + tail
                    physical = logical[-offset:] + logical[:-offset]
                    data = bytearray(buffers[2]);data[0x19000:0x1D000] = physical
                    struct.pack_into("<QII", data, 0x14010, 0xF2019000, 0x4000, 0x80000000 | offset)
                    if complete:
                        dep.verify_device_audit(status, [*buffers[:2], bytes(data)], True)
                    else:
                        with self.assertRaises(ValueError):
                            dep.verify_device_audit(status, [*buffers[:2], bytes(data)], True)
        for field, value in ((0, 0), (0, 0xF2018000), (1, 0), (1, 0x8000), (2, 0x4001), (2, 0x80004001)):
            with self.subTest(field=field, value=value):
                data=bytearray(buffers[0]); meta=[0xF2019000, 0x4000, 80];meta[field]=value
                struct.pack_into("<QII", data, 0x14010, *meta)
                with self.assertRaises(ValueError):dep.plm_debug_text(data)
        data=bytearray(buffers[0]);data[0x19000]=0xFF
        with self.assertRaises(ValueError):dep.plm_debug_text(data)
        with self.assertRaises(ValueError):dep.plm_debug_text(bytes(32))

    def test_guard_sees_deleted_and_new_native_drivers(self):
        proc = self.root / "proc"
        proc.mkdir()
        for pid, name in (
            (11, "GCD-xilinx_alveo_v80_slash"),
            (12, "FireSim-old (deleted)"),
            (13, "python3"),
            (14, "qualified-driver"),
        ):
            directory = proc / str(pid)
            directory.mkdir()
            (directory / "exe").symlink_to("/nonexistent/" + name)
        (proc / "15").mkdir()
        found = load("guard-board").active_drivers(proc)
        self.assertEqual({int(pid) for pid, name in found}, {11, 12, 14})
        with patch("os.readlink", side_effect=PermissionError()), self.assertRaises(
            RuntimeError
        ):
            load("guard-board").active_drivers(proc)

    def test_pcie_link_attributes_reject_lost_or_missing_functions(self):
        root = self.root / "predicted-sysfs"
        for function, attrs in predicted_pcie().items():
            directory = root / function
            directory.mkdir(parents=True)
            for name, value in attrs.items():
                (directory / name).write_text(value + "\n")
        snapshot = dep.read_pcie_links("0000:ab:00", root)
        self.assertEqual(snapshot, predicted_pcie())
        dep.verify_pcie_links(snapshot)
        (root / "0000:ab:00.2/current_link_speed").unlink()
        with self.assertRaisesRegex(ValueError, "link speed unavailable"):
            dep.verify_pcie_links(dep.read_pcie_links("0000:ab:00.2", root))
        with self.assertRaisesRegex(ValueError, "Invalid PCIe BDF"):
            dep.read_pcie_links("../../outside", root)

    def test_pcie_link_validator_rejects_false_healthy_enumeration(self):
        cases = [
            ("vendor", "0xffff"),
            ("device", "0xffff"),
            ("enable", "0"),
            ("current_link_speed", "Unknown"),
            ("current_link_speed", "0 GT/s PCIe"),
            ("current_link_width", "63"),
            ("current_link_width", "0"),
            ("current_link_width", "3"),
            ("current_link_width", None),
            ("current_link_width", "16"),
        ]
        for field, bad in cases:
            snapshot = predicted_pcie()
            snapshot["0000:ab:00.1"][field] = bad
            with self.subTest(field=field, value=bad), self.assertRaises(ValueError):
                dep.verify_pcie_links(snapshot)
        with self.assertRaisesRegex(ValueError, "all three"):
            dep.verify_pcie_links({})

    def test_candidate_requires_acceptance(self):
        self.candidate()
        self.assertFalse(
            dep.verify_bundle(self.bundle, accepted=False)["board_validated"]
        )
        with self.assertRaisesRegex(ValueError, "board acceptance"):
            dep.verify_bundle(self.bundle)
        with self.assertRaisesRegex(ValueError, "Output already exists"):
            self.candidate()

    def test_bad_manager_metadata_rejected_before_export(self):
        for metadata in (
            "GCD NoConfig DefaultV80SlashConfig; offline qualified",
            "firesim-deployquintuplet:xilinx_alveo_v80-midasexamples-GCD-NoConfig-DefaultV80SlashConfig",
            "firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig",
            "firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples--NoConfig-DefaultV80SlashConfig",
            "firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig-DefaultV80SlashConfig,firesim-commit:a:b",
            "firesim-deployquintuplet:xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig-DefaultV80SlashConfig,firesim-commit:a,firesim-commit:b",
        ):
            with self.subTest(metadata=metadata), self.assertRaisesRegex(
                ValueError, "metadata"
            ):
                self.candidate(metadata=metadata)
            self.assertFalse(self.bundle.exists())

    def test_reindexed_metadata_must_remain_manager_readable(self):
        self.candidate()
        (self.bundle / "metadata").write_text("GCD offline qualification only\n")
        path = self.bundle / "deployment.json"
        record = json.loads(path.read_text())
        for entry in record["artifacts"]:
            if entry["path"] == "metadata":
                entry["sha256"] = dep.sha(self.bundle / "metadata")
        path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "metadata"):
            dep.verify_bundle(self.bundle, accepted=False)

    def test_qualified_inputs_are_bound(self):
        mutations = (
            "header",
            "report",
            "image",
            "failed-status",
            "nonfinite",
            "negative",
            "missing-reports",
        )
        original = {p: p.read_bytes() for p in self.q.rglob("*") if p.is_file()}
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                for p, data in original.items():
                    p.write_bytes(data)
                q = json.loads((self.q / "build-qualification.json").read_text())
                if mutation == "header":
                    self.header.write_text("different header")
                if mutation == "report":
                    (self.q / "signoff/timing.txt").write_text("changed")
                if mutation == "image":
                    (self.q / "signoff/partial.pdi").write_bytes(b"changed")
                if mutation == "failed-status":
                    (self.q / "result.json").write_text('{"exit_code": 1}')
                if mutation == "nonfinite":
                    q["timing"]["wns"] = float("nan")
                if mutation == "negative":
                    q["timing"]["whs"] = -0.001
                if mutation == "missing-reports":
                    q["reports"] = []
                (self.q / "build-qualification.json").write_text(json.dumps(q))
                with self.assertRaises(ValueError):
                    self.candidate()
                self.assertFalse(self.bundle.exists())

    def test_manifest_paths_and_mutations(self):
        self.candidate()
        path = self.bundle / "deployment.json"
        original = path.read_bytes()
        for mutation in (
            "duplicate",
            "escape",
            "absolute",
            "symlink",
            "driver",
            "script",
        ):
            with self.subTest(mutation=mutation):
                path.write_bytes(original)
                record = json.loads(original)
                if mutation == "duplicate":
                    record["artifacts"].append(record["artifacts"][0])
                if mutation == "escape":
                    record["artifacts"][0]["path"] = "../predicted-native"
                if mutation == "absolute":
                    record["artifacts"][0]["path"] = str(self.driver)
                if mutation == "symlink":
                    (self.bundle / "outside").symlink_to(self.driver)
                    record["artifacts"].append(
                        dict(path="outside", sha256=dep.sha(self.driver))
                    )
                if mutation == "driver":
                    record["driver_sha256"] = "0" * 64
                if mutation == "script":
                    (self.bundle / "guard-board.py").write_text("changed")
                path.write_text(json.dumps(record))
                with self.assertRaises(ValueError):
                    dep.verify_bundle(self.bundle, accepted=False)

    def test_accept_exact_candidate(self):
        self.candidate()
        evidence, _ = self.evidence()
        out = self.root / "accepted"
        with patch.object(
            sys,
            "argv",
            [
                "export-deployment.py",
                "accept",
                "--bundle",
                str(self.bundle),
                "--board-evidence",
                str(evidence),
                "--output",
                str(out),
            ],
        ), contextlib.redirect_stdout(io.StringIO()):
            self.export.main()
        accepted = out / "xilinx_alveo_v80_slash"
        self.assertTrue(dep.verify_bundle(accepted)["board_validated"])
        self.assertTrue((out / "firesim.tar.gz").is_file())
        # Relocation preserves all bindings and verification needs no host config.
        moved = self.root / "relocated"
        shutil.move(str(accepted), moved)
        result = subprocess.run(
            [
                sys.executable,
                moved / "run-qualified.py",
                "--bundle",
                moved,
                "--bdf",
                "0000:ab:00",
                "--verify-only",
            ],
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        (self.root / "verify.stdout.log").write_text(result.stdout)

    def test_reject_incomplete_or_changed_acceptance(self):
        self.candidate()
        path, original = self.evidence()
        for mutation in (
            "failed",
            "diagnostic",
            "wrong-binding",
            "missing-check",
            "false-check",
            "missing-log",
            "changed-log",
        ):
            with self.subTest(mutation=mutation):
                record = json.loads(json.dumps(original))
                if mutation == "failed":
                    record["exit_code"] = 1
                if mutation == "diagnostic":
                    record["functional_pass"] = False
                if mutation == "wrong-binding":
                    record["deployment_manifest_sha256"] = "0" * 64
                if mutation == "missing-check":
                    del record["checks"]["clock_measure"]
                if mutation == "false-check":
                    record["checks"]["guard_after"] = False
                if mutation == "missing-log":
                    record["logs"] = []
                if mutation == "changed-log":
                    (path.parent / "native.stdout.log").write_text("changed")
                path.write_text(json.dumps(record))
                out = self.root / ("rejected-" + mutation)
                with patch.object(
                    sys,
                    "argv",
                    [
                        "export-deployment.py",
                        "accept",
                        "--bundle",
                        str(self.bundle),
                        "--board-evidence",
                        str(path),
                        "--output",
                        str(out),
                    ],
                ), self.assertRaises(ValueError):
                    self.export.main()
                self.assertFalse(out.exists())

    def runtime(self, mode="pass", args_extra=()):
        self.candidate()
        if mode == "accepted-library-change":
            evidence, _ = self.evidence()
            accepted = self.root / "accepted"
            with patch.object(
                sys,
                "argv",
                [
                    "export-deployment.py",
                    "accept",
                    "--bundle",
                    str(self.bundle),
                    "--board-evidence",
                    str(evidence),
                    "--output",
                    str(accepted),
                ],
            ), contextlib.redirect_stdout(io.StringIO()):
                self.export.main()
            self.bundle = accepted / "xilinx_alveo_v80_slash"
        config = self.root / "host.json"
        config.write_text(
            json.dumps(
                dict(
                    bdf="0000:ab:00",
                    board_lock=str(self.root / "board.lock"),
                    v80_smi="predicted-smi",
                    vivado="predicted-vivado",
                    jtag_hw_server="predicted-server",
                    clk_force="predicted-clock-set",
                    clk_monitor="predicted-clock-read",
                    clock_probe="predicted-probe",
                )
            )
        )
        logdir = self.root / "runtime"
        calls = []

        class Process:
            pid = 999999

            def __init__(inner, cmd, **kwargs):
                inner.command = cmd
                inner.code = None
                calls.append(cmd)
                output = ""
                if cmd[0] == "ldd":
                    output = f"libvrt.so.1 => {self.driver} (0x1)\nlibvrtdpp.so.1 => {self.driver} (0x2)\n"
                    if mode == "missing-library":
                        output += "libjsoncpp.so => not found\n"
                if cmd[0] == "predicted-smi":
                    output = "0000:ab:00 PF0: OK PF1: OK PF2: OK VRTD: OK\n"
                if cmd[0] == "predicted-clock-read":
                    output = "40000000\n" if mode != "bad-clock" else "30000000\n"
                if cmd[0] == str(self.bundle / "qualified-driver"):
                    output = "PREDICTED PASS\n" if mode != "bad-output" else "FAIL\n"
                if "read-device-status.tcl" in " ".join(map(str, cmd)):
                    status, _ = predicted_device()
                    if mode == "bad-device":
                        status = status.replace("_DONE=1", "_DONE=0")
                    Path(cmd[-1]).write_text(status)
                if "read-plm-status.tcl" in " ".join(map(str, cmd)):
                    _, buffers = predicted_device()
                    for slr, data in enumerate(buffers):
                        programmed = any(
                            "program-reviewed-partial-jtag.tcl" in " ".join(map(str, c))
                            for c in calls
                        )
                        if programmed and mode != "stale-program":
                            data = data.replace(
                                b"Boot PDI Load: Started", b"Boot PDI Load: started"
                            )
                        if mode == "incomplete-plm":
                            data = data.replace(
                                b"SBI PDI Load: Done", b"SBI PDI Load: fail"
                            )
                        (Path(cmd[-1]) / f"slr{slr}.bin").write_bytes(data)
                kwargs["stdout"].write(output)
                kwargs["stdout"].flush()

            def wait(inner, timeout=None):
                if (
                    inner.command[0] == str(self.bundle / "qualified-driver")
                    and mode in ("timeout", "interrupt")
                    and inner.code is None
                ):
                    inner.code = -9
                    if mode == "interrupt":
                        raise InterruptedError("predicted controller signal")
                    raise subprocess.TimeoutExpired(inner.command, timeout)
                if (
                    inner.command[0] == "predicted-vivado"
                    and mode == "stop-during-program"
                    and "program-reviewed-partial-jtag.tcl"
                    in " ".join(map(str, inner.command))
                ):
                    import signal

                    signal.raise_signal(signal.SIGTERM)
                inner.code = (
                    7
                    if mode == "native-error"
                    and inner.command[0] == str(self.bundle / "qualified-driver")
                    else 0
                )
                return inner.code

            def poll(inner):
                return inner.code

        connects = iter([1, 1, 0])

        class Socket:
            def __enter__(inner):
                return inner

            def __exit__(inner, *unused):
                pass

            def settimeout(inner, timeout):
                pass

            def connect_ex(inner, address):
                return next(connects)

        module = load("run-qualified")
        pcie_reads = 0

        def pcie_snapshot(bdf):
            nonlocal pcie_reads
            pcie_reads += 1
            snapshot = predicted_pcie()
            if (
                mode == "bad-pcie"
                or (mode == "pcie-lost-after-program" and pcie_reads >= 2)
                or (mode == "pcie-lost-before-native" and pcie_reads >= 3)
            ):
                for attrs in snapshot.values():
                    attrs["current_link_speed"] = "Unknown"
                    attrs["current_link_width"] = "63"
            return snapshot

        argv = [
            "run-qualified.py",
            "--bundle",
            str(self.bundle),
            "--bdf",
            "0000:ab:00",
            "--host-config",
            str(config),
            "--acceptance-test",
            "--expect",
            "PREDICTED PASS",
            "--log-dir",
            str(logdir),
            "--timeout-seconds",
            "20",
            "--",
            "+permissive",
            "+permissive-off",
            *args_extra,
        ]
        if mode == "accepted-library-change":
            argv.remove("--acceptance-test")
        if mode == "lock-timeout":
            argv[argv.index("--timeout-seconds") + 1] = "1"
        real_flock = module.fcntl.flock

        def flock(*args):
            if mode == "lock-timeout":
                raise BlockingIOError("predicted held board lock")
            return real_flock(*args)

        with patch.object(module.fcntl, "flock", flock), patch.object(
            sys, "argv", argv
        ), patch.object(module.os, "geteuid", return_value=0), patch.object(
            module.subprocess, "Popen", Process
        ), patch.object(
            module.socket, "socket", Socket
        ), patch.object(
            module, "read_pcie_links", side_effect=pcie_snapshot
        ), patch.object(
            module.os, "killpg"
        ) as kill, contextlib.redirect_stdout(
            io.StringIO()
        ):
            if mode == "pass":
                self.assertEqual(module.main(), 0)
            else:
                with self.assertRaises((ValueError, TimeoutError, InterruptedError)):
                    module.main()
        return logdir, calls, kill.call_args_list

    def test_runtime_order_and_exact_arguments(self):
        logdir, calls, _ = self.runtime()
        record = json.loads((logdir / "board-acceptance.json").read_text())
        dep.verify_acceptance(record, dep.sha(self.bundle / "deployment.json"))
        native = next(
            cmd for cmd in calls if cmd[0] == str(self.bundle / "qualified-driver")
        )
        self.assertIn("+v80-use-loaded-image", native)
        self.assertLess(
            native.index("+v80-use-loaded-image"), native.index("+permissive-off")
        )
        self.assertEqual(sum(arg.startswith("+bdf=") for arg in native), 1)
        self.assertLess(
            next(i for i, c in enumerate(calls) if c[0] == "predicted-probe"),
            calls.index(native),
        )

    def test_runtime_failures_clean_up_owned_server(self):
        for mode in (
            "missing-library",
            "accepted-library-change",
            "lock-timeout",
            "bad-clock",
            "native-error",
            "bad-output",
            "timeout",
            "interrupt",
            "stop-during-program",
            "bad-device",
            "incomplete-plm",
            "stale-program",
            "bad-pcie",
            "pcie-lost-after-program",
            "pcie-lost-before-native",
        ):
            with self.subTest(mode=mode):
                # Give each simulated board run an independent directory.
                test = DeploymentTest()
                test.setUp()
                try:
                    logdir, calls, kills = test.runtime(mode)
                    record = json.loads((logdir / "board-acceptance.json").read_text())
                    self.assertEqual(record["state"], "failed")
                    self.assertFalse(record["functional_pass"])
                    owner = json.loads((logdir / "controller.json").read_text())
                    self.assertEqual(owner["state"], "exited")
                    self.assertTrue(owner["cleanup_completed"])
                    if mode == "stop-during-program":
                        self.assertTrue(record["checks"]["program"])
                    self.assertEqual(
                        bool(kills),
                        mode
                        not in (
                            "missing-library",
                            "accepted-library-change",
                            "lock-timeout",
                            "bad-pcie",
                        ),
                    )
                    if mode in (
                        "bad-clock",
                        "stop-during-program",
                        "bad-device",
                        "incomplete-plm",
                        "stale-program",
                        "bad-pcie",
                        "pcie-lost-after-program",
                        "pcie-lost-before-native",
                    ):
                        self.assertFalse(
                            any(c[0].endswith("qualified-driver") for c in calls)
                        )
                finally:
                    test.doCleanups()

    def test_invalid_driver_arguments_fail_before_hardware(self):
        for arg in ("+bdf=0000:ff:00", "+binary_file=/different.vbin"):
            test = DeploymentTest()
            test.setUp()
            try:
                logdir, calls, _ = test.runtime("invalid-args", (arg,))
                self.assertEqual(calls, [])
                self.assertFalse(logdir.exists())
            finally:
                test.doCleanups()

    def test_three_slr_status_and_plm_reject_partial_or_inconsistent_loads(self):
        status, buffers = predicted_device()
        for programmed in (False, True):
            self.assertEqual(
                len(dep.verify_device_audit(status, buffers, programmed)["slrs"]), 3
            )
        for mutation in (
            "error",
            "missing",
            "duplicate",
            "not-done",
            "config",
            "readback",
            "short",
            "no-partial",
            "incomplete",
            "ssit",
        ):
            with self.subTest(mutation=mutation):
                s, b = status, list(buffers)
                if mutation == "error":
                    s = s.replace("SLR2=0x4", "SLR2=0xc")
                if mutation == "missing":
                    s = s.replace("REGISTER.ERROR_STATUS.SLR0=0x0", "")
                if mutation == "duplicate":
                    s += "\nREGISTER.ERROR_STATUS.SLR0=0x0"
                if mutation == "not-done":
                    s = s.replace("_DONE=1", "_DONE=0")
                if mutation == "config":
                    s = s.replace("_JCONFIG_ERROR=0", "_JCONFIG_ERROR=1")
                if mutation == "readback":
                    s = s.replace("_JRDBK_ERROR=0", "_JRDBK_ERROR=1")
                if mutation == "short":
                    b[1] = b[1][:-4]
                if mutation == "no-partial":
                    b[2] = b[2].replace(b"SBI PDI Load", b"XXX PDI Load")
                if mutation == "incomplete":
                    b[0] = b[0].replace(b"SBI PDI Load: Done", b"SBI PDI Load: fail")
                if mutation == "ssit":
                    data = bytearray(b[2])
                    struct.pack_into("<I", data, 0x15800, 0xDEADBEEE)
                    b[2] = bytes(data)
                with self.assertRaises(ValueError):
                    dep.verify_device_audit(s, b, True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
