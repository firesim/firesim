#!/usr/bin/env python3
"""Build and run register-level regressions against production MIDAS C++ sources."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def main():
    """Run the selected regressions and preserve their inputs and raw output."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[5])
    parser.add_argument("--output", type=Path, required=True,
                        help="New directory for raw logs, binaries, and source fingerprints")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "g++"))
    parser.add_argument("--timeout", type=float, default=180,
                        help="Total build/test deadline in seconds (default: 180)")
    parser.add_argument("--suite", choices=["all", "fased", "simulation", "scheduler", "loadmem", "fpga-stream", "fpga-push", "fpga-flush"], default="all")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    source = args.source_root.resolve() / "sim/midas/src/main/cc"
    tests = Path(__file__).resolve().parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    files = [
        "bridges/fased_memory_timing_model.cc", "bridges/clock.cc", "bridges/master.cc",
        "bridges/loadmem.cc", "core/address_map.cc", "core/widget.cc",
        "core/bridge_driver.cc", "core/widget_registry.cc", "core/stream_engine.cc",
        "core/simif.cc", "core/simulation.cc", "core/timing.cc",
    ]
    suites = {
        "fased": ("fased_driver_test.cc", [
            "histogram-lifetime", "range-lifetime", "model-index", "output-isolation",
            "defaults", "unsigned32", "hex32", "decimal", "negative", "overflow",
            "trailing-garbage", "empty"]),
        "simulation": ("simulation_flow_test.cc", [
            "success", "late-failure", "early-failure", "multiple-failures",
            "success-before-failure", "nonterminating", "timeout",
            "init-pending", "init-invalid", "init-unavailable",
            "init-wait-timeout", "init-wait-ready", "init-wait-invalid",
            "init-timeout-zero", "init-timeout-negative", "init-timeout-overflow",
            "init-timeout-upper", "init-timeout-empty", "init-timeout-trailing",
            "init-timeout-plus", "init-timeout-space", "init-timeout-duplicate",
            "init-timeout-min", "init-timeout-max"]),
        "scheduler": ("fased_scheduler_test.cc", ["profile-lifetime", "no-profile"]),
        "loadmem": ("loadmem_transport_test.cc", [
            f"{case}-{width}" for width in (64, 512) for case in (
                "legacy-single", "legacy-chunk", "bulk-single", "bulk-chunk",
                "declined", "flush-read", "mixed", "flush-zero", "legacy-file",
                "bulk-file", "zero-length")]),
        "fpga-push": ("fpga_stream_push_test.cc", ["pull-init", "init", "linear", "wrap", "required", "full", "zero", "invalid", "invalid-ring", "corrupt-credit", "unaligned-credit", "doorbell-timeout", "consume-timeout", "flush-timeout", "flush", "counter-wrap"]),
        "fpga-stream": ("fpga_stream_sync_test.cc", ["linear", "wrap", "required", "zero"]),
        "fpga-flush": ("fpga_stream_flush_test.cc", ["immediate", "slow-progress", "timeout", "io-error"]),
    }
    files.extend(["bridges/peek_poke.cc", "core/systematic_scheduler.cc", "bridges/fpga_managed_stream.cc"])
    top = args.source_root.resolve() / "sim/src/main/cc/fasedtests/fasedtests_top.cc"
    fingerprint_files = sorted(set(source.rglob("*.h")) | {source / f for f in files}
                               | set(tests.glob("*.cc")) | {top, Path(__file__).resolve()})
    manifest = {
        "source_commit": subprocess.check_output(
            ["git", "-C", str(args.source_root.resolve()), "rev-parse", "HEAD"], text=True).strip(),
        "source_status": subprocess.check_output(
            ["git", "-C", str(args.source_root.resolve()), "status", "--porcelain"], text=True),
        "source_sha256": {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in fingerprint_files},
        "compiler": subprocess.check_output([args.cxx, "--version"], text=True).splitlines()[0],
        "hardware_access": False,
        "sanitizer": "AddressSanitizer; stack-use-after-return enabled; leak checking disabled",
        "suite": args.suite,
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "build_timeout_seconds": 180,
        "case_timeout_seconds": 20,
        "total_timeout_seconds": args.timeout,
        "randomness": "none (deterministic register-level tests)",
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    start = time.monotonic()
    deadline = start + args.timeout
    results = {"suites": {}, "hardware_access": False}
    # AddrRangeCounter has an existing raw allocation without a destructor;
    # this suite diagnoses invalid accesses, not unrelated exit-time leaks.
    env = dict(os.environ, ASAN_OPTIONS="detect_leaks=0:detect_stack_use_after_return=1")

    def run_bounded(command, stdout, stderr, timeout, cwd=None):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            stderr.write("\nTotal build/test deadline exceeded.\n")
            return 124
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr,
                                   cwd=cwd, env=env, start_new_session=True)
        try:
            return process.wait(timeout=min(timeout, remaining))
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            stderr.write("\nBuild/test deadline exceeded; process group stopped.\n")
            return 124
    for suite, (test_file, case_names) in suites.items():
        if args.suite not in ("all", suite):
            continue
        suite_output = output / suite
        suite_output.mkdir()
        binary = suite_output / Path(test_file).stem
        command = [args.cxx, "-std=c++20", "-O1", "-g", "-fsanitize=address",
                   "-fno-omit-frame-pointer", "-fno-pie", "-no-pie", "-I" + str(source),
                   "-I" + str(top.parent), str(tests / test_file),
                   *[str(source / f) for f in files], "-lgmp", "-o", str(binary)]
        (suite_output / "build-command.json").write_text(json.dumps(command, indent=2) + "\n")
        with (suite_output / "build.stdout").open("w") as stdout, (suite_output / "build.stderr").open("w") as stderr:
            build_code = run_bounded(command, stdout, stderr, 180)
        suite_result = {"build_exit_code": build_code, "cases": {}}
        results["suites"][suite] = suite_result
        if build_code != 0:
            print(f"{suite}: BUILD FAILED ({build_code})", flush=True)
            continue
        for name in case_names:
            work = suite_output / name
            work.mkdir()
            with (work / "stdout.log").open("w") as stdout, (work / "stderr.log").open("w") as stderr:
                code = run_bounded([str(binary), name], stdout, stderr, 20, cwd=work)
            suite_result["cases"][name] = code
            print(f"{suite}/{name}: {'PASS' if code == 0 else 'FAIL'} ({code})", flush=True)
    results["elapsed_seconds"] = time.monotonic() - start
    (output / "result.json").write_text(json.dumps(results, indent=2) + "\n")
    return int(any(s["build_exit_code"] != 0 or any(s["cases"].values())
                   for s in results["suites"].values()))


if __name__ == "__main__":
    sys.exit(main())
