#!/usr/bin/env python3
# See LICENSE for license details.
"""Reject a packaged control aperture incompatible with the resident SLASH shell."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def check(component):
    tree = ET.parse(component).getroot()
    maps = [
        m
        for m in tree.findall(".//{*}memoryMap")
        if m.findtext("{*}name") == "s_axi_control"
    ]
    if len(maps) != 1:
        raise ValueError("Expected one s_axi_control memory map")
    blocks = maps[0].findall("{*}addressBlock")
    if len(blocks) != 1 or blocks[0].findtext("{*}name") != "reg0":
        raise ValueError("Expected one control register address block")
    size = int(blocks[0].findtext("{*}range", "0"), 0)
    if size != 134217728:
        raise ValueError(
            f"Static-shell NoC control aperture must be 128 MiB; packaged range is {size}"
        )
    return dict(control_aperture_bytes=size, hardware_access=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(check(args.component)))


if __name__ == "__main__":
    main()
