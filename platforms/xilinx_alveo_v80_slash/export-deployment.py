#!/usr/bin/env python3
# See LICENSE for license details.
"""Export an offline-qualified test candidate, then promote its exact accepted files."""
import argparse
import json
from pathlib import Path
import shutil
import tarfile
from deployment import (
    partial_image,
    require,
    sha,
    verify_acceptance,
    verify_bundle,
    verify_entries,
    verify_metadata,
    verify_qualified,
)

PLATFORM = Path(__file__).resolve().parent


def inventory(root):
    return [
        dict(path=str(p.relative_to(root)), sha256=sha(p))
        for p in sorted(root.rglob("*"))
        if p.is_file() and p.name != "deployment.json"
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    candidate = modes.add_parser("candidate")
    candidate.add_argument("--qualified", type=Path, required=True)
    candidate.add_argument("--driver", type=Path, required=True)
    candidate.add_argument("--header", type=Path, required=True)
    candidate.add_argument("--metadata", required=True)
    candidate.add_argument("--output", type=Path, required=True)
    accept = modes.add_parser("accept")
    accept.add_argument("--bundle", type=Path, required=True)
    accept.add_argument("--board-evidence", type=Path, required=True)
    accept.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    require(not out.exists(), "Output already exists")
    if args.mode == "candidate":
        verify_metadata(args.metadata)
        qualified = args.qualified.resolve(strict=True)
        original = verify_qualified(qualified)
        require(
            args.header.read_bytes()
            == (qualified / "FireSim-generated.const.h").read_bytes(),
            "Native build register map differs from qualified hardware",
        )
        driver = args.driver.resolve(strict=True)
        require(
            driver.is_file() and driver.stat().st_mode & 0o111,
            "Native driver is not executable",
        )
        # Resolve and verify all input artifacts before creating an output.
        sources = [
            (qualified / item["path"], item["path"]) for item in original["artifacts"]
        ]
        sources.extend(
            (qualified / item["path"], "reports/" + item["path"])
            for item in original["reports"]
        )
        sources.extend(
            [
                (driver, "qualified-driver"),
                (qualified / "build-qualification.json", "build-qualification.json"),
            ]
        )
        sources.extend(
            (PLATFORM / name, name)
            for name in (
                "deployment.py",
                "run-qualified.py",
                "stop-qualified.py",
                "guard-board.py",
                "program-reviewed-partial-jtag.tcl",
                "read-device-status.tcl",
                "read-plm-status.tcl",
            )
        )
        image = partial_image(qualified / "firesim.vbin")
        require(
            image == (qualified / "signoff/partial.pdi").read_bytes(),
            "Image differs from the actual signoff output",
        )
        out.mkdir(parents=True, exist_ok=False)
        for source, name in sources:
            target = out / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        (out / "partial.pdi").write_bytes(image)
        (out / "metadata").write_text(args.metadata + "\n")
        record = dict(
            schema_version=2,
            platform="xilinx_alveo_v80_slash",
            board_validated=False,
            clock_hz=original["clock_hz"],
            timing=original["timing"],
            driver_sha256=sha(driver),
            header_sha256=sha(args.header),
            qualification_sha256=sha(qualified / "build-qualification.json"),
            artifacts=inventory(out),
        )
        (out / "deployment.json").write_text(json.dumps(record, indent=2) + "\n")
        verify_bundle(out, accepted=False)
        print(out)
    else:
        bundle = args.bundle.resolve(strict=True)
        original = verify_bundle(bundle, accepted=False)
        require(
            original["board_validated"] is False,
            "Use the original unpromoted candidate",
        )
        evidence = json.loads(args.board_evidence.read_text())
        verify_acceptance(evidence, sha(bundle / "deployment.json"))
        # Every raw log is rechecked locally before promoting the evidence.
        evidence_root = args.board_evidence.resolve().parent
        verify_entries(evidence_root, evidence["logs"])
        out.mkdir(parents=True, exist_ok=False)
        stage = out / "xilinx_alveo_v80_slash"
        shutil.copytree(bundle, stage)
        shutil.copy2(args.board_evidence, stage / "board-acceptance.json")
        for entry in evidence["logs"]:
            target = stage / "acceptance-logs" / entry["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(evidence_root / entry["path"], target)
        record = dict(
            original,
            board_validated=True,
            candidate_manifest_sha256=sha(bundle / "deployment.json"),
            runtime_libraries=evidence["runtime_libraries"],
            artifacts=inventory(stage),
        )
        (stage / "deployment.json").write_text(json.dumps(record, indent=2) + "\n")
        verify_bundle(stage)
        with tarfile.open(out / "firesim.tar.gz", "w:gz") as archive:
            archive.add(stage, arcname="xilinx_alveo_v80_slash")
        print(out / "firesim.tar.gz")


if __name__ == "__main__":
    main()
