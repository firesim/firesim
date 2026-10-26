#!/usr/bin/env python3
# See LICENSE for license details.
"""Bounded CPU checks for production metasim stream IO; device responses are predicted."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root", type=Path, default=Path(__file__).resolve().parents[5]
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("Timeout must be positive")
    root = args.source_root.resolve()
    source = root / "sim/midas/src/main/cc"
    tests = Path(__file__).resolve().parent
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    deadline = started + args.timeout
    files = [
        source / "emul" / name
        for name in ("simif_emul.cc", "mm.cc", "mmio.cc", "qsfp.cc")
    ]
    inputs = (
        set(files)
        | set(source.rglob("*.h"))
        | {tests / "metasim_stream_test.cc", Path(__file__).resolve()}
    )
    manifest = dict(
        commit=subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
        ).strip(),
        status=subprocess.check_output(
            ["git", "-C", str(root), "status", "--porcelain"], text=True
        ),
        sha256={
            str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs)
        },
        timeout_seconds=args.timeout,
        hardware_access=False,
        started_at_utc=datetime.now(timezone.utc).isoformat(),
        model="Production simif_emul and memory mappings; predicted MMIO responses; no lifecycle thread or RTL",
        sanitizer="ASan + UBSan + leak checking",
    )
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(command, directory, limit):
        (directory / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        with (directory / "stdout.log").open("w") as stdout, (
            directory / "stderr.log"
        ).open("w") as stderr:
            process = subprocess.Popen(
                command,
                cwd=directory,
                stdout=stdout,
                stderr=stderr,
                start_new_session=True,
                env=dict(
                    os.environ,
                    ASAN_OPTIONS="detect_leaks=1:detect_stack_use_after_return=1",
                ),
            )
            try:
                return process.wait(
                    timeout=max(0.01, min(limit, deadline - time.monotonic()))
                )
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                return 124

    result = dict(hardware_access=False, variants={})
    variants = {
        "legacy": (
            "",
            [
                "no-stream",
                "legacy-cpu",
                "legacy-fpga",
                "shared-rings",
                "shared-overflow",
                "shared-small",
                "shared-channels",
            ],
        ),
        "ddr": (
            "#define V80_DDR_STREAM_METADATA\n#define V80_DDR_STREAM_MIN_DDR 65536ULL\n"
            + "".join(
                f"#define V80_DDR_STREAM_{name} {256 + 4 * i}\n"
                for i, name in enumerate(
                    ("DDR_LO", "DDR_HI", "STREAM", "BYTES", "COMMAND", "STATUS")
                )
            ),
            ["roundtrip", "zero", "invalid", "small-staging"],
        ),
        "mmio": (
            "#define V80_MMIO_STREAM_METADATA\n"
            + "".join(
                f"#define V80_MMIO_STREAM_{name} {256 + 4 * i}\n"
                for i, name in enumerate(("ADDRESS", "TX", "RX", "READ_WORDS"))
            ),
            ["roundtrip", "zero", "invalid"],
        ),
    }
    for variant, (metadata, cases) in variants.items():
        directory = out / variant
        directory.mkdir()
        (directory / "FireSim-generated.const.h").write_text(metadata)
        binary = directory / "metasim-stream-test"
        command = [
            "g++",
            "-std=c++20",
            "-O1",
            "-g",
            "-fsanitize=address,undefined",
            "-fno-omit-frame-pointer",
            "-fno-pie",
            "-no-pie",
            "-ffunction-sections",
            "-fdata-sections",
            "-I" + str(directory),
            "-I" + str(source),
            str(tests / "metasim_stream_test.cc"),
            *map(str, files),
            "-Wl,--gc-sections",
            "-pthread",
            "-o",
            str(binary),
        ]
        record = dict(build_exit_code=run(command, directory, 60), cases={})
        result["variants"][variant] = record
        if record["build_exit_code"] == 0:
            for name in cases:
                case = directory / name
                case.mkdir()
                code = run([str(binary), name], case, 15)
                record["cases"][name] = code
                print(
                    f"{variant}/{name}: {'PASS' if code == 0 else 'FAIL'} ({code})",
                    flush=True,
                )
        if time.monotonic() >= deadline:
            break
    result["exit_code"] = int(
        len(result["variants"]) != len(variants)
        or any(
            r["build_exit_code"] != 0 or any(r["cases"].values())
            for r in result["variants"].values()
        )
    )
    result["elapsed_seconds"] = time.monotonic() - started
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)
    return result["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
