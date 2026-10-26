#!/usr/bin/env python3
"""CPU-only orchestration checks; fake Vivado reports are explicitly predicted."""
import hashlib
import importlib.util
import io
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import xml.etree.ElementTree as ET

PLATFORM = Path(__file__).resolve().parents[1]
SCRIPTS = PLATFORM / "cl_firesim/scripts"


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildToolsTest(unittest.TestCase):
    def setUp(self):
        parent = os.environ.get("SLASH_TEST_OUTPUT_DIR")
        if parent:
            self.root = Path(
                tempfile.mkdtemp(prefix=self._testMethodName + "-", dir=parent)
            )
        else:
            self.temp = tempfile.TemporaryDirectory(prefix="slash predicted build ")
            self.addCleanup(self.temp.cleanup)
            self.root = Path(self.temp.name)
        self.commands = 0

    def command(self, command, **kwargs):
        self.commands += 1
        command = list(map(str, command))
        result = subprocess.run(
            command, capture_output=True, text=True, timeout=30, **kwargs
        )
        record = self.root / f"command-{self.commands}"
        record.mkdir()
        (record / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        (record / "stdout.log").write_text(result.stdout)
        (record / "stderr.log").write_text(result.stderr)
        (record / "result.json").write_text(
            json.dumps(dict(exit_code=result.returncode)) + "\n"
        )
        return result

    def test_explicit_vivado_selection(self):
        selected = self.root / "selected Vivado"
        alternate = self.root / "alternate Vivado"
        for directory in (selected, alternate):
            (directory / "bin").mkdir(parents=True)
            tool = directory / "bin/vivado"
            tool.write_text("#!/bin/sh\nexit 0\n")
            tool.chmod(0o755)
        (self.root / "Makefile").write_text("vbin:\n\t@command -v vivado\n")
        result = self.command(
            [
                PLATFORM / "build-bitstream.sh",
                "--build-dir",
                self.root,
                "--frequency",
                40,
                "--timeout-seconds",
                5,
                "--log-dir",
                self.root / "logs",
            ],
            env=dict(
                os.environ,
                XILINX_VIVADO=str(selected),
                PATH=str(alternate / "bin") + os.pathsep + os.environ["PATH"],
            ),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            str(selected / "bin/vivado"), (self.root / "logs/stdout.log").read_text()
        )
        manifest = json.loads((self.root / "logs/manifest.json").read_text())
        self.assertEqual(manifest["tool_inputs"]["XILINX_VIVADO"], str(selected))

    def test_fixed_static_control_aperture_with_variable_rtl_width(self):
        package = (SCRIPTS / "package_kernel.tcl").read_text()
        procedure = re.search(
            r"(?ms)^proc detect_ctrl_window_bytes \{\} \{.*?^\}", package
        ).group()
        script = self.root / "detect.tcl"
        script.write_text(
            procedure
            + "\nset path_to_hdl [lindex $argv 0]\nset base_filename FireSim-generated\nputs [detect_ctrl_window_bytes]\n"
        )
        rtl = self.root / "FireSim-generated.sv"
        for width in (12, 20, 22):
            rtl.write_text(f"input wire [{width - 1}:0] s_axi_lite_awaddr;\n")
            result = self.command(["tclsh", script, self.root])
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines()[-1], "134217728")
        for width in (5, 23):
            rtl.write_text(f"input wire [{width - 1}:0] s_axi_lite_awaddr;\n")
            self.assertNotEqual(
                self.command(["tclsh", script, self.root]).returncode, 0
            )
        rtl.write_text("module missing_control(); endmodule\n")
        self.assertNotEqual(self.command(["tclsh", script, self.root]).returncode, 0)
        component = self.root / "component.xml"
        template = (
            '<component xmlns="urn:ieee:std:1685:2009"><memoryMaps><memoryMap>'
            "<name>s_axi_control</name><addressBlock><name>reg0</name><range>{}</range>"
            "</addressBlock></memoryMap></memoryMaps></component>"
        )
        check = load("check-control-aperture").check
        for value in ("134217728", "0x08000000"):
            component.write_text(template.format(value))
            self.assertEqual(check(component)["control_aperture_bytes"], 134217728)
        for value in ("4096", "65536", "0", "268435456", "garbage"):
            component.write_text(template.format(value))
            with self.assertRaises(ValueError):
                check(component)
        component.write_text(
            template.format(134217728).replace("s_axi_control", "other")
        )
        with self.assertRaises(ValueError):
            check(component)

    def test_static_comparison(self):
        compare = load("compare-static-config").compare
        record = self.root / "records.txt"
        good = (
            "first|SLICE_X1Y1|static/lut|INIT|0\nsecond|SLICE_X1Y1|static/lut|INIT|0\n"
        )
        record.write_text(good)
        self.assertEqual(compare(record)["differences"], [])
        record.write_text(good.replace("INIT|0\nsecond", "INIT|1\nsecond"))
        self.assertEqual(len(compare(record)["differences"]), 1)
        for bad in (
            "",
            "unrecognized\n",
            good.splitlines()[0],
            good + good.splitlines()[0] + "\n",
        ):
            with self.subTest(bad=bad):
                record.write_text(bad)
                with self.assertRaises(ValueError):
                    compare(record)

    def test_link_clock_and_switching(self):
        make = PLATFORM / "cl_firesim/Makefile"
        import shutil

        shutil.copytree(SCRIPTS, self.root / "scripts")
        for frequency in (40, 30, 40):
            result = self.command(
                ["make", "-f", make, f"FREQUENCY={frequency}", "link.cfg"],
                cwd=self.root,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(
                f"freqhz={frequency * 1000000}\n", (self.root / "link.cfg").read_text()
            )
        for frequency in (0, 301, "invalid"):
            with self.subTest(frequency=frequency):
                result = self.command(
                    [
                        sys.executable,
                        SCRIPTS / "write-link-config.py",
                        "--frequency",
                        frequency,
                        "--output",
                        self.root / "rejected.cfg",
                    ]
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / "rejected.cfg").exists())

    def test_build_exit_and_timeout(self):
        for mode, recipe, expected in (
            ("pass", "@echo success", 0),
            ("failure", "@exit 7", 2),
            ("timeout", "@sleep 60", 124),
        ):
            with self.subTest(mode=mode):
                (self.root / "Makefile").write_text("vbin:\n\t" + recipe + "\n")
                logs = self.root / mode
                result = self.command(
                    [
                        PLATFORM / "build-bitstream.sh",
                        "--build_dir",
                        self.root,
                        "--frequency",
                        40,
                        "--timeout-seconds",
                        1,
                        "--log-dir",
                        logs,
                    ]
                )
                self.assertEqual(result.returncode, expected, result.stderr)
                recorded = json.loads((logs / "result.json").read_text())
                self.assertEqual(recorded["exit_code"], expected)
                self.assertFalse(recorded["hardware_access"])
                self.assertEqual(recorded["timed_out"], mode == "timeout")
                self.assertTrue(
                    (logs / "stdout.log").is_file() and (logs / "stderr.log").is_file()
                )
                self.assertLess(recorded["elapsed_seconds"], 12)

    def fixture(self, mode):
        build = self.root / mode
        build.mkdir()
        (build / "design").mkdir()
        for name in ("FireSim-generated.const.h", "FireSim-generated.sv"):
            (build / "design" / name).write_text("predicted fixture")
        routed = (
            build
            / "firesim.vbin.prj/slash_rm/slash_firesim.runs/impl_1/top_wrapper_routed.dcp"
        )
        routed.parent.mkdir(parents=True)
        routed.write_text("predicted routed checkpoint")
        for name in ("shell.dcp", "baseline.dcp"):
            (build / name).write_text("predicted static checkpoint")
        entries = [
            ("images/old.pdi", b"old predicted image"),
            (
                "system_map.xml",
                b"<System><ClockFrequency>200000000</ClockFrequency></System>",
            ),
        ]
        if mode == "archive-path":
            entries.append(("../escape", b"bad"))
        if mode == "archive-duplicate":
            entries.append(entries[0])
        if mode == "archive-clock-count":
            entries[1] = ("system_map.xml", b"<System/>")
        with tarfile.open(build / "firesim.vbin", "w:gz") as archive:
            for name, data in entries:
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        return build

    def qualify(self, mode):
        build = self.fixture(mode)
        result = self.command(
            [
                sys.executable,
                SCRIPTS / "qualify-build.py",
                "--build-dir",
                build,
                "--shell",
                build / "shell.dcp",
                "--static-baseline",
                build / "baseline.dcp",
                "--frequency",
                40,
                "--timeout-seconds",
                1 if mode == "timeout" else 20,
                "--vivado",
                PLATFORM / "tests/fake-vivado.py",
            ],
            env=dict(os.environ, SLASH_TEST_REPORT_MODE=mode),
        )
        return result, build / "qualified"

    def test_qualification_binds_archive_and_reports(self):
        result, output = self.qualify("pass")
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((output / "build-qualification.json").read_text())
        self.assertFalse(manifest["native_driver_board_validated"])
        self.assertEqual(manifest["clock_hz"], 40000000)
        self.assertEqual(manifest["static_review_sites"], ["SLICE_X1Y1"])
        for record in manifest["artifacts"]:
            self.assertEqual(
                hashlib.sha256((output / record["path"]).read_bytes()).hexdigest(),
                record["sha256"],
            )
        for record in manifest["reports"]:
            self.assertEqual(
                hashlib.sha256((output / record["path"]).read_bytes()).hexdigest(),
                record["sha256"],
            )
        with tarfile.open(output / "firesim.vbin") as archive:
            root = ET.fromstring(archive.extractfile("system_map.xml").read())
            self.assertEqual(root.find("ClockFrequency").text, "40000000")
            images = [m for m in archive.getmembers() if m.name.endswith(".pdi")]
            self.assertEqual(len(images), 1)
            self.assertEqual(archive.extractfile(images[0]).read(), b"predicted image")

    def test_qualification_rejects_invalid_proofs_and_archives(self):
        for mode in (
            "tool-error",
            "timing-fail",
            "waveform-fail",
            "clock-fail",
            "drc-fail",
            "pr-fail",
            "archive-path",
            "archive-duplicate",
            "archive-clock-count",
            "timeout",
        ):
            with self.subTest(mode=mode):
                result, output = self.qualify(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((output / "build-qualification.json").exists())
                self.assertEqual(
                    json.loads((output / "status.json").read_text())["state"], "failed"
                )
                self.assertNotEqual(
                    json.loads((output / "result.json").read_text())["exit_code"], 0
                )

    def test_debug_probe_rebasing(self):
        rebase = load("export-debug-probes").rebase
        identities = {
            "top_i/slash/firesim_0/inst/ila": dict(uuid="A" * 32, probe_count=1)
        }
        layout = {
            "ltx_root": {
                "ltx_data": [
                    {
                        "debug_cores": [
                            dict(
                                name="ila",
                                uuid="0" * 32,
                                pins=[dict(nets=[dict(name="wire")])],
                            )
                        ]
                    }
                ]
            }
        }
        changes = rebase(layout, identities)
        core = layout["ltx_root"]["ltx_data"][0]["debug_cores"][0]
        self.assertEqual(core["uuid"], "A" * 32)
        self.assertEqual(
            core["pins"][0]["nets"][0]["name"], "top_i/slash/firesim_0/inst/wire"
        )
        self.assertEqual(len(changes), 1)
        with self.assertRaises(ValueError):
            rebase({"ltx_root": {"ltx_data": []}}, identities)


if __name__ == "__main__":
    unittest.main(verbosity=2)
