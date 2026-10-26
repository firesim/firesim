# See LICENSE for license details.
"""Shared, hardware-free checks for SLASH qualification and deployment records."""
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import tarfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_pcie_links(bdf, sysfs=Path("/sys/bus/pci/devices")):
    """Capture Linux PCIe link attributes without accessing a device BAR."""
    require(
        re.fullmatch(r"[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}(?:\.[0-7])?", bdf),
        "Invalid PCIe BDF",
    )
    functions = {}
    for function in range(3):
        name = bdf.split(".")[0] + "." + str(function)
        values = {}
        for attr in (
            "vendor",
            "device",
            "enable",
            "current_link_speed",
            "current_link_width",
        ):
            try:
                values[attr] = (sysfs / name / attr).read_text().strip()
            except OSError:
                values[attr] = None
        functions[name] = values
    return functions


def verify_pcie_links(functions):
    """Reject missing functions and all-ones/unknown PCIe link status."""
    require(len(functions) == 3, "Expected all three PCIe functions")
    links = []
    for name, values in functions.items():
        require(values.get("vendor") == "0x10ee", f"{name}: PCIe vendor unavailable")
        device = values.get("device")
        require(
            isinstance(device, str)
            and re.fullmatch(r"0x[0-9a-fA-F]{4}", device)
            and 0 < int(device, 16) < 0xFFFF,
            f"{name}: PCIe device unavailable",
        )
        require(values.get("enable") == "1", f"{name}: PCIe function disabled")
        speed = values.get("current_link_speed")
        require(
            isinstance(speed, str)
            and re.fullmatch(r"[0-9]+(?:\.[0-9]+)? GT/s PCIe", speed)
            and float(speed.split()[0]) > 0,
            f"{name}: PCIe link speed unavailable",
        )
        width = values.get("current_link_width")
        require(
            width in {"1", "2", "4", "8", "16", "32"},
            f"{name}: PCIe link width invalid",
        )
        links.append((speed, width))
    require(len(set(links)) == 1, "PCIe function link states differ")



def plm_debug_text(data):
    """Decode the Versal PLM debug ring using its captured RTCA metadata.

    embeddedsw xilplmi 2025.1: XPlmi_CircularBuffer in
    xplmi_event_logging.h; XPLMI_RTCFG_DBG_LOG_BUF_ADDR in xplmi.h;
    XPLMI_DEBUG_LOG_BUFFER_ADDR/LEN in versal/server/xplmi_hw.h.
    outbyte() wraps at Len and stores the next byte offset plus full flag.
    Never infer completion from stale or out-of-ring printable memory.
    """
    require(len(data) == 131072, "Incomplete PMC buffer")
    start, length, packed = struct.unpack_from("<QII", data, 0x14010)
    offset, full = packed & 0x7FFFFFFF, packed >> 31
    require(start == 0xF2019000 and length == 0x4000,
            "Unsupported PLM debug ring layout")
    require(offset <= length, "Invalid PLM debug ring offset")
    ring = data[0x19000:0x1D000]
    ordered = ring[offset:] + ring[:offset] if full else ring[:offset]
    require(all(byte in (9, 10, 13) or 32 <= byte <= 126 for byte in ordered),
            "Invalid PLM debug ring text")
    return ordered.decode("ascii")


def verify_device_audit(status, plm, programmed):
    """Check read-only three-SLR status and PMC buffers, never mutate the card.

    SLR1/2 expose the static shell's SSIT routing bits (1/2 respectively).
    All other error bits are rejected, including PLM/boot-ROM/configuration errors.
    A successful JTAG command alone does not prove that a partial PDI loaded.
    """
    rows, vectors = [], []
    require(len(plm) == 3, "Expected three SLR PMC buffers")
    for slr, allowed in enumerate((0, 2, 4)):
        errors = re.findall(
            rf"^REGISTER\.ERROR_STATUS\.SLR{slr}=(0x[0-9a-fA-F]+)$",
            status,
            re.M,
        )
        require(
            len(errors) == 1 and int(errors[0], 16) & ~allowed == 0,
            f"SLR{slr} unexpected error status",
        )
        for bit, expected in (
            ("DONE", "1"),
            ("JCONFIG_ERROR", "0"),
            ("JRDBK_ERROR", "0"),
        ):
            values = re.findall(
                rf"^REGISTER\.JTAG_STATUS\.SLR{slr}\.BIT\[\d+\]_{bit}=(\d+)$",
                status,
                re.M,
            )
            require(values == [expected], f"SLR{slr} invalid {bit}")
        data = plm[slr]
        require(len(data) == 131072, f"SLR{slr} incomplete PMC buffer")
        text = plm_debug_text(data)
        marker = (
            "SBI PDI Load"
            if programmed or "SBI PDI Load: Started" in text
            else "Boot PDI Load"
        )
        require(
            text.rfind(marker + ": Done") > text.rfind(marker + ": Started") >= 0,
            f"SLR{slr} incomplete {marker}",
        )
        vectors.append(list(struct.unpack_from("<III", data, 0x15800)))
        rows.append(dict(slr=slr, error_status=errors[0], done=True))
    require(
        vectors[0][0] == vectors[1][0] and vectors[0][1] == vectors[2][0],
        "Master/slave SSIT state differs",
    )
    return dict(slrs=rows, ssit_vectors=vectors, partial_load_required=programmed)


def inside(root, name):
    path = Path(name)
    require(
        not path.is_absolute() and ".." not in path.parts and path.as_posix() == name,
        "Noncanonical artifact path",
    )
    target = (root / path).resolve(strict=True)
    require(target.is_relative_to(root) and target.is_file(), "Artifact outside bundle")
    return target


def verify_entries(root, entries):
    names = set()
    for entry in entries:
        require(entry["path"] not in names, "Duplicate artifact")
        names.add(entry["path"])
        require(
            sha(inside(root, entry["path"])) == entry["sha256"],
            "Artifact changed: " + entry["path"],
        )
    return names


def verify_timing(record):
    require(
        type(record["clock_hz"]) is int and 1000000 <= record["clock_hz"] <= 300000000,
        "Invalid runtime clock",
    )
    timing = record["timing"]
    require(
        all(
            type(timing[k]) in (int, float) and math.isfinite(timing[k])
            for k in ("wns", "whs", "wpws", "tns", "ths", "tpws")
        ),
        "Invalid timing values",
    )
    require(
        all(timing[k] >= 0 for k in ("wns", "whs", "wpws"))
        and all(timing[k] == 0 for k in ("tns", "ths", "tpws")),
        "Timing qualification failed",
    )


def verify_qualified(root):
    record = json.loads((root / "build-qualification.json").read_text())
    require(
        record["schema_version"] == 1
        and record["native_driver_board_validated"] is False,
        "Invalid offline qualification",
    )
    require(
        json.loads((root / "result.json").read_text())["exit_code"] == 0
        and json.loads((root / "status.json").read_text())
        == dict(
            stage="offline-build-qualified", state="completed", hardware_tested=False
        ),
        "Offline qualification did not finish",
    )
    verify_timing(record)
    names = verify_entries(root, record["artifacts"])
    require(
        {"firesim.vbin", "FireSim-generated.const.h"} <= names,
        "Missing qualified artifacts",
    )
    require(bool(record["reports"]), "No qualification reports")
    verify_entries(root, record["reports"])
    return record


def partial_image(package):
    images = []
    names = set()
    with tarfile.open(package, "r:gz") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            require(
                not path.is_absolute()
                and ".." not in path.parts
                and path.as_posix() == member.name
                and member.isfile()
                and member.name not in names,
                "Unsafe vbin member",
            )
            names.add(member.name)
            if member.name.endswith(".pdi"):
                images.append(archive.extractfile(member).read())
    require(len(images) == 1, "Expected exactly one partial image")
    return images[0]


def verify_metadata(text):
    """Require metadata that the Manager's hardware database reader can consume."""
    tags = {}
    for entry in text.strip().split(","):
        pair = entry.split(":")
        require(
            len(pair) == 2
            and all(pair)
            and pair[0] not in tags
            and not any(char.isspace() for char in entry),
            "Invalid Manager deployment metadata",
        )
        tags[pair[0]] = pair[1]
    pieces = tags.get("firesim-deployquintuplet", "").split("-")
    require(
        len(pieces) >= 5 and all(pieces) and pieces[0] == "xilinx_alveo_v80_slash",
        "Manager metadata must select the SLASH deploy quintuplet",
    )
    return tags


def verify_bundle(root, accepted=True):
    record = json.loads((root / "deployment.json").read_text())
    require(
        record["schema_version"] == 2
        and record["platform"] == "xilinx_alveo_v80_slash",
        "Invalid SLASH deployment",
    )
    require(
        type(record["board_validated"]) is bool
        and (not accepted or record["board_validated"]),
        "Deployment has not completed board acceptance",
    )
    verify_timing(record)
    names = verify_entries(root, record["artifacts"])
    actual_files = {str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()}
    require(actual_files == names | {"deployment.json"}, "Unindexed deployment files")
    required = {
        "firesim.vbin",
        "partial.pdi",
        "qualified-driver",
        "FireSim-generated.const.h",
        "build-qualification.json",
        "metadata",
        "deployment.py",
        "run-qualified.py",
        "stop-qualified.py",
        "guard-board.py",
        "program-reviewed-partial-jtag.tcl",
        "read-device-status.tcl",
        "read-plm-status.tcl",
    }
    require(required <= names, "Incomplete deployment")
    verify_metadata((root / "metadata").read_text())
    require(
        sha(root / "qualified-driver") == record["driver_sha256"]
        and sha(root / "FireSim-generated.const.h") == record["header_sha256"],
        "Driver/header binding differs",
    )
    require(
        sha(root / "build-qualification.json") == record["qualification_sha256"],
        "Offline qualification binding differs",
    )
    qualified = json.loads((root / "build-qualification.json").read_text())
    require(
        qualified["clock_hz"] == record["clock_hz"]
        and qualified["timing"] == record["timing"],
        "Deployment differs from offline timing qualification",
    )
    verify_entries(root, qualified["artifacts"])
    verify_entries(
        root,
        [
            dict(path="reports/" + entry["path"], sha256=entry["sha256"])
            for entry in qualified["reports"]
        ],
    )
    require(
        partial_image(root / "firesim.vbin") == (root / "partial.pdi").read_bytes(),
        "Packaged partial differs",
    )
    if record["board_validated"]:
        require("board-acceptance.json" in names, "Missing board acceptance record")
        evidence = json.loads((root / "board-acceptance.json").read_text())
        verify_acceptance(evidence, record["candidate_manifest_sha256"])
        require(
            record["runtime_libraries"] == evidence["runtime_libraries"],
            "Runtime library binding differs",
        )
    return record


def verify_acceptance(evidence, manifest_sha):
    require(
        evidence["state"] == "completed"
        and evidence["hardware_tested"] is True
        and evidence["exit_code"] == 0
        and evidence["functional_pass"] is True,
        "Board acceptance did not pass",
    )
    require(
        evidence["deployment_manifest_sha256"] == manifest_sha,
        "Board run used a different candidate",
    )
    checks = evidence["checks"]
    required = {
        "guard_before",
        "device_before",
        "device_programmed",
        "device_after",
        "health_before",
        "program",
        "clock_readback",
        "clock_measure",
        "native_driver",
        "functional_output",
        "guard_after",
        "health_after",
        "runtime_libraries",
        "pcie_before",
        "pcie_programmed",
        "pcie_native",
        "pcie_after",
    }
    require(
        required <= checks.keys() and all(value is True for value in checks.values()),
        "Incomplete or failed board checks",
    )
    require(bool(evidence["logs"]), "Acceptance has no raw log bindings")
    libraries = evidence["runtime_libraries"]
    require(
        bool(libraries)
        and all(
            isinstance(name, str)
            and isinstance(digest, str)
            and len(digest) == 64
            and all(c in "0123456789abcdef" for c in digest)
            for name, digest in libraries.items()
        ),
        "Invalid runtime library fingerprints",
    )
