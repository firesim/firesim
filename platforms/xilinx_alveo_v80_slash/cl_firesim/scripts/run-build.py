#!/usr/bin/env python3
"""Run a bounded SLASH build, preserving commands, source hashes and raw logs."""
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
    parser.add_argument("--build_dir", "--build-dir", type=Path, required=True)
    parser.add_argument("--frequency", type=int, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=43200)
    parser.add_argument("--log-dir", type=Path)
    args = parser.parse_args()
    if not 1 <= args.frequency <= 300 or args.timeout_seconds <= 0:
        parser.error("frequency must be in [1, 300] MHz and timeout must be positive")
    build = args.build_dir.resolve(strict=True)
    if not (build / "Makefile").is_file():
        parser.error("build directory must contain a Makefile")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = (args.log_dir or build / "build-logs" / stamp).resolve()
    output.mkdir(parents=True, exist_ok=False)
    command = ["make", "-C", str(build), f"FREQUENCY={args.frequency}", "vbin"]
    sources = [build / "Makefile"]
    sources += [
        p
        for directory in ("design", "scripts")
        for p in (build / directory).rglob("*")
        if p.is_file() and "__pycache__" not in p.parts
    ]
    env = dict(os.environ)
    if env.get("XILINX_VIVADO"):
        env["PATH"] = (
            str(Path(env["XILINX_VIVADO"]) / "bin") + os.pathsep + env.get("PATH", "")
        )
    manifest = dict(
        command=command,
        timeout_seconds=args.timeout_seconds,
        tool_inputs={key: env.get(key) for key in ("XILINX_VIVADO", "SLASH_DIR")},
        started_at_utc=datetime.now(timezone.utc).isoformat(),
        hardware_access=False,
        source_sha256={
            str(p.relative_to(build)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(sources)
        },
    )
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    started = time.monotonic()
    code, timed_out = 1, False
    process = None
    try:
        with (output / "stdout.log").open("w") as out, (output / "stderr.log").open(
            "w"
        ) as err:
            process = subprocess.Popen(
                command, stdout=out, stderr=err, start_new_session=True, env=env
            )
            try:
                code = process.wait(timeout=args.timeout_seconds)
            except subprocess.TimeoutExpired:
                timed_out = True
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                code = 124
    finally:
        result = dict(
            exit_code=code,
            timed_out=timed_out,
            elapsed_seconds=time.monotonic() - started,
            hardware_access=False,
        )
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(dict(log_dir=str(output), **result)), flush=True)
    raise SystemExit(code)


if __name__ == "__main__":
    main()
