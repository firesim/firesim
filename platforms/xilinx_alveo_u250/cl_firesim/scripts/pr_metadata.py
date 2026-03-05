#!/usr/bin/env python3
"""PR metadata management for FireSim Partial Reconfiguration builds.

Generates metadata during initial PR builds (main_pr.tcl) and validates
source file consistency during RM builds (main_pr_rm.tcl).

Designed to support querying a database of Vivado projects to find
compatible ones for partial reconfiguration (matching partition paths,
configs, and outer module checksums).

Usage:
    # After main_pr.tcl completes — generate metadata
    python3 pr_metadata.py generate \
        --root_dir /path/to/build \
        --frequency 60 \
        --strategy TIMING \
        --board au250 \
        --part xcu250-figd2104-2l-e \
        --board_part xilinx.com:au250:part0:1.3 \
        --pr_module_names Rocket \
        --pr_partition_paths firesim_top/.../core \
        [--discovered_paths_file /path/to/discovered_paths.txt]

    # Before main_pr_rm.tcl — validate sources haven't changed
    python3 pr_metadata.py validate \
        --root_dir /path/to/current_build \
        --project_path /path/to/old_project.xpr \
        --pr_module_names Rocket \
        --pr_partition_paths firesim_top/.../core \
        --frequency 60
"""

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime
from pathlib import Path


def file_md5(filepath: str) -> str:
    h = hashlib.md5()
    with open(filepath, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def compute_source_checksums(root_dir: str) -> dict[str, str]:
    """Compute MD5 checksums for all design source files under root_dir.

    Scans design/split-verilog/ first (per-module .sv files), then
    design/ for .sv, .v, .vh files. Returns {relative_path: md5}.
    """
    checksums = {}
    root = Path(root_dir)

    for subdir in ["design/split-verilog", "design"]:
        d = root / subdir
        if not d.is_dir():
            continue
        for ext in ("*.sv", "*.v", "*.vh"):
            for f in sorted(d.glob(ext)):
                relpath = str(f.relative_to(root))
                if relpath not in checksums:
                    checksums[relpath] = file_md5(str(f))

    return checksums


def generate_metadata(args):
    """Generate pr_metadata.json after a main_pr.tcl build."""
    module_names = [n.strip() for n in args.pr_module_names.split(",")]
    partition_paths = []

    # If a discovered_paths_file exists (written by post_synth_pr.tcl),
    # it replaces command-line names/paths since discovery may expand
    # one module name into multiple instances. Format: "ModuleName:path/to/cell"
    if args.discovered_paths_file and os.path.exists(args.discovered_paths_file):
        module_names = []
        partition_paths = []
        with open(args.discovered_paths_file) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    mod, path = line.split(":", 1)
                    module_names.append(mod.strip())
                    partition_paths.append(path.strip())
    elif args.pr_partition_paths:
        partition_paths = [p.strip() for p in args.pr_partition_paths.split(",")]

    # Build per-module structure
    unique_modules = []
    for m in module_names:
        if m not in unique_modules:
            unique_modules.append(m)

    pr_modules = []
    for umod in unique_modules:
        paths = [
            partition_paths[i]
            for i in range(len(module_names))
            if module_names[i] == umod
        ]
        pr_modules.append({
            "module_name": umod,
            "partition_paths": paths,
            "partition_def": f"pr_partition_{umod}",
            "reconfig_module": f"pr_reconfig_module_{umod}",
        })

    checksums = compute_source_checksums(args.root_dir)

    metadata = {
        "build_timestamp": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "vivado_version": args.vivado_version or "",
        "part": args.part or "",
        "board_part": args.board_part or "",
        "frequency_mhz": args.frequency,
        "strategy": args.strategy,
        "top_level_name": "overall_fpga_top",
        "pr_modules": pr_modules,
        "source_checksums": checksums,
    }

    output_path = os.path.join(args.root_dir, "vivado_proj", "pr_metadata.json")
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w") as f:
        json.dump(metadata, f, indent=2)

    print(f"PR metadata written to: {output_path}")
    print(f"  Modules: {[m['module_name'] for m in pr_modules]}")
    print(f"  Source files checksummed: {len(checksums)}")


def validate_metadata(args):
    """Validate current sources against stored PR metadata before main_pr_rm.tcl."""
    project_dir = os.path.dirname(args.project_path)
    metadata_path = os.path.join(project_dir, "pr_metadata.json")

    if not os.path.exists(metadata_path):
        print(f"WARNING: No PR metadata found at {metadata_path}")
        print("  Cannot validate source file consistency.")
        print("  Run the initial build with the updated flow to generate metadata.")
        return

    with open(metadata_path) as f:
        metadata = json.load(f)

    module_names = [n.strip() for n in args.pr_module_names.split(",")]
    current_checksums = compute_source_checksums(args.root_dir)
    stored_checksums = metadata.get("source_checksums", {})

    print("=" * 50)
    print("PR SOURCE VALIDATION")
    print("=" * 50)
    if "build_timestamp" in metadata:
        print(f"  Original build: {metadata['build_timestamp']}")

    changed_pr_files = []
    changed_static_files = []
    missing_files = []
    new_files = []

    for relpath, stored_md5 in stored_checksums.items():
        if relpath in current_checksums:
            if stored_md5 != current_checksums[relpath]:
                is_pr = any(
                    relpath.endswith(f"/{mod}.sv") or relpath == f"{mod}.sv"
                    for mod in module_names
                )
                if is_pr:
                    changed_pr_files.append(relpath)
                else:
                    changed_static_files.append(relpath)
        else:
            missing_files.append(relpath)

    for relpath in current_checksums:
        if relpath not in stored_checksums:
            new_files.append(relpath)

    if changed_pr_files:
        print("  PR module files changed (expected):")
        for f in changed_pr_files:
            print(f"    [OK] {f}")

    has_warnings = False

    if changed_static_files:
        has_warnings = True
        print()
        print("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        print("  WARNING: NON-PR SOURCE FILES HAVE CHANGED!")
        print("  The static portion of the design may be out of date.")
        print("  The existing project's synthesis/implementation was")
        print("  done with different source files. The resulting")
        print("  bitstream may be INCORRECT.")
        print("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        print()
        for f in changed_static_files:
            print(f"    [CHANGED] {f}")

    if missing_files:
        has_warnings = True
        print()
        print("  WARNING: Source files from original build are missing:")
        for f in missing_files:
            print(f"    [MISSING] {f}")

    if new_files:
        print()
        print("  NOTE: New source files not in original build:")
        for f in new_files:
            print(f"    [NEW] {f}")

    if not has_warnings and not changed_static_files:
        print("  All non-PR source files match the original build.")

    print("=" * 50)

    # Validate build parameters
    stored_freq = str(metadata.get("frequency_mhz", ""))
    if stored_freq and stored_freq != str(args.frequency):
        print(f"WARNING: Frequency mismatch: original='{stored_freq}' current='{args.frequency}'")

    stored_part = metadata.get("part", "")
    if stored_part and args.part and stored_part != args.part:
        print(f"WARNING: Part mismatch: original='{stored_part}' current='{args.part}'")

    stored_board = metadata.get("board_part", "")
    if stored_board and args.board_part and stored_board != args.board_part:
        print(f"WARNING: Board part mismatch: original='{stored_board}' current='{args.board_part}'")

    # Validate partition paths
    if args.pr_partition_paths:
        partition_paths = [p.strip() for p in args.pr_partition_paths.split(",")]
        stored_modules = metadata.get("pr_modules", [])
        all_stored_paths = {}
        for mod in stored_modules:
            for pp in mod.get("partition_paths", []):
                all_stored_paths[pp] = mod["module_name"]

        for pp in partition_paths:
            if pp not in all_stored_paths:
                print(f"WARNING: Partition path '{pp}' was not in the original PR build.")
                print("  Available paths from original build:")
                for sp, mn in all_stored_paths.items():
                    print(f"    - {sp} ({mn})")

    if has_warnings:
        sys.exit(2)


def main():
    parser = argparse.ArgumentParser(description="PR metadata management")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # generate subcommand
    gen = subparsers.add_parser("generate", help="Generate PR metadata after build")
    gen.add_argument("--root_dir", required=True)
    gen.add_argument("--frequency", required=True)
    gen.add_argument("--strategy", required=True)
    gen.add_argument("--part", default="")
    gen.add_argument("--board_part", default="")
    gen.add_argument("--vivado_version", default="")
    gen.add_argument("--pr_module_names", required=True,
                     help="Comma-separated PR module names")
    gen.add_argument("--pr_partition_paths", default="",
                     help="Comma-separated partition paths")
    gen.add_argument("--discovered_paths_file", default="",
                     help="File with discovered module:path lines from post_synth_pr.tcl")

    # validate subcommand
    val = subparsers.add_parser("validate", help="Validate sources before RM build")
    val.add_argument("--root_dir", required=True)
    val.add_argument("--project_path", required=True,
                     help="Path to the existing .xpr project file")
    val.add_argument("--pr_module_names", required=True)
    val.add_argument("--pr_partition_paths", default="")
    val.add_argument("--frequency", default="")
    val.add_argument("--part", default="")
    val.add_argument("--board_part", default="")

    args = parser.parse_args()

    if args.command == "generate":
        generate_metadata(args)
    elif args.command == "validate":
        validate_metadata(args)


if __name__ == "__main__":
    main()
