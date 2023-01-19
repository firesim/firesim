#!/usr/bin/env python3

# Flash Vitis/XRT-based FPGAs with proper shell
# Requires running with sudo and having XRT sourced

import subprocess
import json

def run_wrapper(run_str: str) -> None:
    r = subprocess.run(run_str)
    cmd = " ".join(r.args)
    if r.returncode != 0:
        assert False, f"Unable to run '{cmd}'"

json_file = "/tmp/examine-out.json"

run_wrapper(f"sudo /opt/xilinx/xrt/bin/xbmgmt examine --format JSON -o {json_file} --force".split())

with open(json_file, "r") as f:
    json_dict = json.loads(f.read())
card_bdfs = [d["bdf"] for d in json_dict["system"]["host"]["devices"]]

for bdf in card_bdfs:
    run_wrapper(f"sudo /opt/xilinx/xrt/bin/xbmgmt examine -d {bdf} -r platform -f json --output {json_file} --force".split())

    with open(json_file, "r") as f:
        json_dict = json.loads(f.read())

    dev_dict = json_dict["devices"]
    assert len(dev_dict) == 1, "Unsupported number of devices"

    shell_dict = dev_dict[0]["platform"]["available_shells"]
    assert len(shell_dict) == 1, "Unsupported number of shells"

    shell_file = shell_dict[0]["file"]

    run_wrapper(f"sudo /opt/xilinx/xrt/bin/xbmgmt program -d {bdf} --shell {shell_file}".split())
