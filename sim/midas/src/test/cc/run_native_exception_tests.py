#!/usr/bin/env python3
"""Check real pthread/DPI exception cleanup with a previously generated GCD."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[5])
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "g++"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    root = args.source_root.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    generated = output / "generated"
    generated.mkdir()
    for path in args.generated.resolve().iterdir():
        if path.is_file() and path.suffix in (".sv", ".vh", ".h", ".v"):
            shutil.copy2(path, generated / path.name)
    tests = Path(__file__).resolve().parent
    cc = root / "sim/midas/src/main/cc"
    target_cc = root / "sim/src/main/cc/midasexamples"
    inputs = list(generated.iterdir()) + list(cc.rglob("*.cc")) + list(cc.rglob("*.h"))
    inputs += [tests / "metasim_driver_exception.cc", target_cc / "TestHarness.cc", Path(__file__)]
    manifest = dict(
        commit=subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
        status=subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True),
        timeout_seconds=args.timeout,
        hardware_access=False,
        source_sha256={str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
    )
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    started = time.monotonic()
    deadline = started + args.timeout

    def run(name, command, limit):
        (output / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        with (output / (name + ".stdout.log")).open("w") as stdout, (output / (name + ".stderr.log")).open("w") as stderr:
            process = subprocess.Popen(command, cwd=output, stdout=stdout, stderr=stderr, start_new_session=True)
            try:
                return process.wait(timeout=max(0.01, min(limit, deadline - time.monotonic())))
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                return 124

    flags = os.environ.get("CXXFLAGS", "") + " -O1 -g -D RTLSIM -DFIRESIM -I" + str(target_cc)
    command = ["make", "-C", str(cc), "-j8", "VM_PARALLEL_BUILDS=1", "PLATFORM=xilinx_alveo_v80_slash",
               "DRIVER_NAME=GCD", "GEN_FILE_BASENAME=FireSim-generated", "GEN_DIR=" + str(generated),
               "CXX=" + args.cxx, "CXXFLAGS=" + flags,
               "DRIVER=" + str(tests / "metasim_driver_exception.cc") + " " + str(target_cc / "TestHarness.cc"),
               "verilator"]
    result = dict(hardware_access=False, build_exit_code=run("build", command, args.timeout), cases={})
    if result["build_exit_code"] == 0:
        for name, extra, message in [("standard", [], "intentional driver exception regression"),
                                     ("nonstandard", ["+throw-nonstandard"], "unknown exception")]:
            code = run(name, [str(generated / "VGCD"), *extra], 10)
            error = (output / (name + ".stderr.log")).read_text()
            result["cases"][name] = dict(exit_code=code, passed=code == 1 and "Metasimulation driver failed: " + message in error)
    result["elapsed_seconds"] = time.monotonic() - started
    result["exit_code"] = int(result["build_exit_code"] != 0 or len(result["cases"]) != 2 or
                              any(not c["passed"] for c in result["cases"].values()))
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
