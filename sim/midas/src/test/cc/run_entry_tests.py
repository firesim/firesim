#!/usr/bin/env python3
"""Check native process exits and cleanup against the production main.cc."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root", type=Path, default=Path(__file__).resolve().parents[5]
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "g++"))
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    root = args.source_root.resolve()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    cc = root / "sim/midas/src/main/cc"
    stub = Path(__file__).resolve().parent / "native_entry_stub.cc"
    binary = out / "native-entry-test"
    command = [
        args.cxx,
        "-std=c++17",
        "-O1",
        "-g",
        "-fsanitize=address",
        "-fno-omit-frame-pointer",
        "-fno-pie",
        "-no-pie",
        "-I" + str(cc),
        str(cc / "main.cc"),
        str(stub),
        "-o",
        str(binary),
    ]
    inputs = [cc / "main.cc", cc / "entry.h", stub, Path(__file__).resolve()]
    manifest = dict(
        commit=subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
        ).strip(),
        status=subprocess.check_output(
            ["git", "-C", str(root), "status", "--porcelain"], text=True
        ),
        command=command,
        timeout_seconds=args.timeout,
        hardware_access=False,
        source_sha256={
            str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs
        },
    )
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    start = time.monotonic()
    deadline = start + args.timeout
    env = dict(
        os.environ, ASAN_OPTIONS="detect_leaks=0:detect_stack_use_after_return=1"
    )

    def run(name, cmd):
        with (out / (name + ".stdout.log")).open("w") as so, (
            out / (name + ".stderr.log")
        ).open("w") as se:
            process = subprocess.Popen(
                cmd, stdout=so, stderr=se, env=env, start_new_session=True
            )
            try:
                return process.wait(
                    timeout=max(0.01, min(30, deadline - time.monotonic()))
                )
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                return 124

    result = dict(
        hardware_access=False, build_exit_code=run("build", command), cases={}
    )
    cases = [
        ("success", 0, None),
        ("failure-code", 17, None),
        ("standard", 1, "intentional entry failure"),
        ("nonstandard", 1, "unknown exception"),
    ]
    if result["build_exit_code"] == 0:
        for name, expected, error in cases:
            code = run(name, [str(binary), name])
            text = (out / (name + ".stderr.log")).read_text()
            passed = code == expected and text.count("local cleaned up") == 1
            passed = (
                passed
                and text.count("global cleaned up") == 1
                and "terminate called" not in text
            )
            passed = passed and (
                (("Simulation driver failed: " + error) in text)
                if error
                else "Simulation driver failed:" not in text
            )
            result["cases"][name] = dict(exit_code=code, passed=passed)
    result["elapsed_seconds"] = time.monotonic() - start
    result["exit_code"] = int(
        result["build_exit_code"] != 0
        or len(result["cases"]) != 4
        or any(not c["passed"] for c in result["cases"].values())
    )
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
