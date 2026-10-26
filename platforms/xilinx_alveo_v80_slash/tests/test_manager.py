#!/usr/bin/env python3
# See LICENSE for license details.
"""Exercise actual Manager classes with predicted CAD/board replies and local files."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import shlex
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import MagicMock, patch

PLATFORM = Path(__file__).resolve().parents[1]
REPO = PLATFORM.parents[1]
sys.path.insert(0, str(REPO / "deploy"))
sys.path.insert(0, str(PLATFORM / "tests"))
import test_deployment as fixtures
import deployment as dep
from buildtools import bitbuilder as bb
from runtools import run_farm_deploy_managers as dm
from runtools import runtime_config as rc


class ManagerTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.DeploymentTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.commands = []

    def run_local(self, command, **kwargs):
        self.commands.append(shlex.split(command))
        result = subprocess.run(
            shlex.split(command), capture_output=True, text=True, timeout=30
        )
        prefix = self.root / ("manager-command-" + str(len(self.commands)))
        prefix.with_suffix(".stdout.log").write_text(result.stdout)
        prefix.with_suffix(".stderr.log").write_text(result.stderr)
        prefix.with_suffix(".command.json").write_text(json.dumps(shlex.split(command)))
        result.check_returncode()
        return result.stdout

    def accepted(self):
        self.fixture.candidate()
        evidence, _ = self.fixture.evidence()
        output = self.root / "accepted"
        with patch.object(
            sys,
            "argv",
            [
                "export-deployment.py",
                "accept",
                "--bundle",
                str(self.fixture.bundle),
                "--board-evidence",
                str(evidence),
                "--output",
                str(output),
            ],
        ), contextlib.redirect_stdout(io.StringIO()):
            self.fixture.export.main()
        return output

    def manager(self):
        remote = self.root / "remote sim slot"
        remote.mkdir()
        database = self.root / "fpga db.json"
        host = self.root / "private host.json"
        host.write_text("{}")
        database.write_text(json.dumps([dict(bdf="0000:ab:00", host_config=str(host))]))
        hardware = NS(
            get_local_driver_binaryname=lambda: "FireSim-xilinx_alveo_v80_slash",
            get_bitstream_tar_filename=lambda: "firesim.tar.gz",
        )
        server = NS(get_resolved_server_hardware_config=lambda: hardware)
        parent = NS(
            metasimulation_enabled=False,
            sim_slots=[server],
            switch_slots=[],
            pipe_slots=[],
            get_fpga_db=lambda: str(database),
        )
        manager = dm.XilinxAlveoV80SlashInstanceDeployManager(parent)
        manager.get_remote_sim_dir_for_slot = lambda slot: str(remote)
        return manager, remote, database

    def test_independent_platform_and_explicit_mapping(self):
        manager, remote, database = self.manager()
        self.assertFalse(manager.NEED_ENUMERATION)
        self.assertFalse(isinstance(manager, dm.XilinxAlveoV80InstanceDeployManager))
        with patch.object(dm, "run", self.run_local):
            self.assertEqual(manager.slot_entry(0)["bdf"], "0000:ab:00")
            self.assertTrue(Path(manager.host_config(0)).is_absolute())
            for record in (
                [],
                [dict(bdf="bad")],
                [dict(bdf="0000:ab:00", host_config="relative")],
            ):
                database.write_text(json.dumps(record))
                with self.assertRaises(ValueError):
                    manager.host_config(0)

    def test_real_copy_verify_and_launch_wrapper(self):
        accepted = self.accepted()
        manager, remote, _ = self.manager()
        shutil.copy2(accepted / "firesim.tar.gz", remote / "firesim.tar.gz")
        shutil.copy2(self.fixture.header, remote / self.fixture.header.name)
        with patch.object(dm, "run", self.run_local), patch.object(
            dm, "put", shutil.copy2
        ), patch.object(dm, "get_deploy_dir", return_value=str(REPO / "deploy")):
            manager.copy_bitstreams()
            manager.install_qualified_driver(0)
        bundle = remote / "xilinx_alveo_v80_slash"
        dep.verify_bundle(bundle)
        wrapper = remote / "FireSim-xilinx_alveo_v80_slash"
        executed = []
        with patch.object(
            sys, "argv", [str(wrapper), "+permissive", "+permissive-off"]
        ), patch("os.execvp", side_effect=lambda program, args: executed.append(args)):
            runpy.run_path(str(wrapper), run_name="__main__")
        command = executed[0]
        self.assertIn("--host-config", command)
        self.assertNotIn("--acceptance-test", command)
        self.assertIn("--log-dir", command)
        pointer = json.loads((remote / ".v80-slash-active-run.json").read_text())
        self.assertEqual(command[command.index("--log-dir") + 1], pointer["proof"])
        # A pending/active previous launch cannot be silently replaced.
        with patch.object(sys, "argv", [str(wrapper)]), patch(
            "os.execvp"
        ), self.assertRaises(RuntimeError):
            runpy.run_path(str(wrapper), run_name="__main__")
        (Path(pointer["proof"])).mkdir()
        (Path(pointer["proof"]) / "controller.json").write_text('{"state":"exited"}')
        with patch.object(sys, "argv", [str(wrapper)]), patch("os.execvp"):
            runpy.run_path(str(wrapper), run_name="__main__")
        # A Manager-generated header from another elaboration is rejected.
        (remote / self.fixture.header.name).write_text("mismatched map")
        with patch.object(dm, "run", self.run_local), self.assertRaises(
            subprocess.CalledProcessError
        ):
            manager.install_qualified_driver(0)

    def test_accepted_archive_resolves_actual_hwdb_metadata(self):
        accepted = self.accepted()
        hardware = rc.RuntimeHWConfig(
            "predicted-gcd",
            dict(
                # Existing Manager/fsspec treats local URI escapes literally.
                # Preserve the deliberate spaces to test real shell quoting;
                # this test covers accepted metadata, not URL decoding.
                bitstream_tar="file://" + str(accepted / "firesim.tar.gz"),
                custom_runtime_config=None,
            ),
            str(self.root / "config_hwdb.yaml"),
        )
        # Stock RuntimeHWConfig runs an unquoted tar command in its cache.
        # Keep this metadata test within that existing path restriction;
        # platform launch/copy tests above retain their paths with spaces.
        cache_temp = tempfile.TemporaryDirectory(prefix="slash-hwdb-cache-")
        self.addCleanup(cache_temp.cleanup)
        cache = Path(cache_temp.name)
        hardware.fetch_all_URI(str(cache))
        with patch.object(
            rc,
            "local",
            side_effect=lambda command, **kwargs: self.run_local(
                shlex.join(["/bin/sh", "-c", command])
            ),
        ):
            hardware.resolve_hwcfg_values(str(cache))
        self.assertEqual(hardware.get_platform(), "xilinx_alveo_v80_slash")
        self.assertEqual(
            hardware.get_deployquintuplet_for_config(),
            "xilinx_alveo_v80_slash-midasexamples-GCD-NoConfig-DefaultV80SlashConfig",
        )

    def test_unpack_preserves_existing_and_rejects_links(self):
        accepted = self.accepted()
        directory = self.root / "unpack"
        directory.mkdir()
        unpack = fixtures.load("unpack-deployment")
        argv = [
            "unpack-deployment.py",
            "--archive",
            str(accepted / "firesim.tar.gz"),
            "--destination",
            str(directory),
        ]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
            unpack.main()
            unpack.main()
        before = dep.sha(directory / "xilinx_alveo_v80_slash/deployment.json")
        import tarfile

        for member in (
            "../escape",
            "/absolute",
            "xilinx_alveo_v80_slash/../../escape",
            "xilinx_alveo_v80_slash/link",
        ):
            bad = self.root / "bad.tar.gz"
            with tarfile.open(bad, "w:gz") as archive:
                entry = tarfile.TarInfo(member)
                entry.type = tarfile.SYMTYPE
                entry.linkname = "/tmp/escape"
                archive.addfile(entry)
            with patch.object(
                sys,
                "argv",
                [
                    "unpack-deployment.py",
                    "--archive",
                    str(bad),
                    "--destination",
                    str(directory),
                ],
            ), self.assertRaises(ValueError):
                unpack.main()
            self.assertEqual(
                dep.sha(directory / "xilinx_alveo_v80_slash/deployment.json"), before
            )
        (directory / "xilinx_alveo_v80_slash/extra.py").write_text("unindexed")
        with patch.object(sys, "argv", argv), self.assertRaises(ValueError):
            unpack.main()

    def test_stop_authenticates_pid_start_and_command(self):
        self.fixture.candidate()
        bundle = self.fixture.bundle
        proc = self.root / "proc"
        (proc / "42").mkdir(parents=True)
        fields = ["S"] + ["0"] * 18 + ["12345"]
        (proc / "42/stat").write_text("42 (name with ) spaces) " + " ".join(fields))
        command = ["python3", str(bundle / "run-qualified.py"), "--bundle", str(bundle)]
        (proc / "42/cmdline").write_bytes("\0".join(command).encode() + b"\0")
        owner = dict(
            pid=42,
            start_ticks="12345",
            bundle=str(bundle),
            deployment_manifest_sha256=dep.sha(bundle / "deployment.json"),
        )
        helper = fixtures.load("stop-qualified")
        self.assertEqual(helper.verify_owner(owner, bundle, proc), 42)
        for key, value in (
            ("start_ticks", "54321"),
            ("pid", 1),
            ("bundle", "/another"),
        ):
            with self.assertRaises(ValueError):
                helper.verify_owner(dict(owner, **{key: value}), bundle, proc)
        (proc / "42/cmdline").write_bytes(b"python3\0unrelated.py\0")
        with self.assertRaises(ValueError):
            helper.verify_owner(owner, bundle, proc)

    def test_pidfd_available_in_manager_python(self):
        helper = fixtures.load("stop-qualified")
        descriptor = helper.open_pidfd(os.getpid())
        try:
            helper.send_pidfd_signal(descriptor, 0)
        finally:
            os.close(descriptor)

    def test_stop_uses_pidfd_and_checks_cleanup(self):
        self.fixture.candidate()
        bundle = self.fixture.bundle
        proof = self.root / "proof"
        proof.mkdir()
        path = proof / "controller.json"
        helper = fixtures.load("stop-qualified")
        for mode in ("pass", "timeout", "wrong-owner"):
            path.write_text(json.dumps(dict(state="running")))

            def terminate(descriptor, signum):
                self.assertEqual(descriptor, 567)
                if mode == "pass":
                    path.write_text(
                        json.dumps(dict(state="exited", cleanup_completed=True))
                    )

            argv = [
                "stop-qualified.py",
                "--bundle",
                str(bundle),
                "--proof",
                str(proof),
                "--host-config",
                str(self.root / "unused-private-config"),
                "--timeout-seconds",
                "1",
            ]
            with patch.object(sys, "argv", argv), patch.object(
                helper.os, "geteuid", return_value=0
            ), patch.object(
                helper,
                "verify_owner",
                side_effect=(
                    ValueError("wrong owner") if mode == "wrong-owner" else None
                ),
                return_value=42,
            ), patch.object(
                helper, "open_pidfd", return_value=567
            ), patch.object(
                helper.os, "close"
            ), patch.object(
                helper, "send_pidfd_signal", side_effect=terminate
            ) as signal_call, patch.object(
                helper.select,
                "select",
                return_value=([567] if mode == "pass" else [], [], []),
            ), contextlib.redirect_stdout(
                io.StringIO()
            ):
                if mode == "pass":
                    self.assertEqual(helper.main(), 0)
                else:
                    with self.assertRaises(ValueError):
                        helper.main()
                self.assertEqual(
                    signal_call.call_count, 0 if mode == "wrong-owner" else 1
                )

    def test_start_and_metasimulation_paths(self):
        manager, remote, _ = self.manager()
        server = manager.parent_node.sim_slots[0]
        server.mkdir_and_prep_local_job_results_dir = MagicMock()
        server.write_sim_start_script = MagicMock(
            return_value=str(self.root / "sim-run.sh")
        )
        server.get_sim_kill_command = MagicMock(return_value="predicted-metasim-stop")
        for metasim in (False, True):
            manager.parent_node.metasimulation_enabled = metasim
            with patch.object(
                manager, "slot_entry", return_value=dict(bdf="0000:ab:00")
            ), patch.object(dm, "put") as copy, patch.object(
                dm, "run"
            ) as run, patch.object(
                dm, "cd", contextlib.nullcontext
            ):
                manager.start_sim_slot(0)
            extra = server.write_sim_start_script.call_args.args[1]
            self.assertEqual(extra, None if metasim else "+slotid=0 +bdf=0000:ab:00")
            copy.assert_called_once()
            if metasim:
                with patch.object(dm, "run") as run:
                    manager.kill_sim_slot(0)
                run.assert_called_once_with("predicted-metasim-stop")

    def builder(self, frequency=40.0):
        farm = MagicMock()
        config = NS(
            build_config_file=NS(build_farm=farm),
            get_chisel_quintuplet=lambda: "tiny",
            get_build_dir_name=lambda: "build-1",
            get_frequency=lambda: frequency,
        )
        args = dict(
            slash_dir="/reviewed SLASH",
            vivado_prefix="/reviewed Vivado",
            static_shell="/reviewed shell.dcp",
            static_baseline="/reviewed baseline.dcp",
            vrt_home="/reviewed SDK",
        )
        return bb.XilinxAlveoV80SlashBitBuilder(config, args), farm

    def test_builder_bounds_failures_and_release(self):
        for failure in (0, 1, 2, 3):
            with self.subTest(failure=failure):
                builder, farm = self.builder()
                calls = []

                def run(command, **kwargs):
                    calls.append(shlex.split(command))
                    return NS(return_code=1 if len(calls) == failure else 0)

                with patch.object(
                    builder, "cl_dir_setup", return_value="/snapshot/cl_tiny"
                ), patch.object(builder, "copy_files") as copies, patch.object(
                    bb, "run", run
                ), patch.object(
                    bb, "get_deploy_dir", return_value=str(self.root / "deploy")
                ):
                    self.assertEqual(builder.build_bitstream(), failure == 0)
                farm.release_build_host.assert_called_once_with(builder.build_config)
                self.assertEqual(len(calls), failure or 3)
                self.assertEqual(copies.call_count, 1)
                if failure in (0, 3):
                    self.assertIn("43200", calls[1])
                    self.assertIn("28800", calls[2])
                    self.assertEqual(calls[1][calls[1].index("--frequency") + 1], "40")
                self.assertFalse(
                    any(
                        "tar" in command or any("hwdb" in arg for arg in command)
                        for command in calls
                    )
                )
        for frequency in (0, 40.5, 301, float("nan")):
            builder, farm = self.builder(frequency)
            with patch.object(
                builder, "cl_dir_setup", return_value="/snapshot/cl_tiny"
            ), self.assertRaises(ValueError):
                builder.build_bitstream()
            farm.release_build_host.assert_called_once()
        builder, farm = self.builder()
        self.assertTrue(builder.build_bitstream(bypass=True))
        farm.release_build_host.assert_called_once()

    def test_builder_local_snapshot_is_unique_and_quotes_paths(self):
        local = self.root / "local source"
        source = local / "platforms/xilinx_alveo_v80_slash"
        (local / "deploy").mkdir(parents=True)
        for name in ("cl_firesim", "dependencies", "cl_tiny"):
            (source / name).mkdir(parents=True)
            (source / name / "input.txt").write_text("predicted input")
        for name in ("build-bitstream.sh", "prepare-slash.py"):
            (source / name).write_text("predicted script")
        (source / "cl_tiny/firesim.vbin.prj").mkdir()
        (source / "cl_tiny/firesim.vbin.prj/not-uploaded.dcp").write_text(
            "stale generated CAD"
        )
        builder, _ = self.builder()
        with patch.object(
            bb, "get_deploy_dir", return_value=str(local / "deploy")
        ), patch.object(bb, "on_localhost", return_value=True), patch.object(
            bb, "run", self.run_local
        ), patch.object(
            bb, "local", self.run_local
        ):
            output = Path(builder.cl_dir_setup("tiny", str(self.root / "remote build")))
            self.assertEqual((output / "input.txt").read_text(), "predicted input")
            self.assertTrue((output.parent / "cl_firesim/input.txt").is_file())
            self.assertFalse((output / "firesim.vbin.prj").exists())
            with self.assertRaises(subprocess.CalledProcessError):
                builder.cl_dir_setup("tiny", str(self.root / "remote build"))
            self.assertEqual((output / "input.txt").read_text(), "predicted input")


if __name__ == "__main__":
    unittest.main(verbosity=2)
