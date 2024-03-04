#!/usr/bin/env python3

import argparse
import os
import subprocess
import sys
import pwd
import re
import shutil
import signal
import json
from pathlib import Path
import pcielib
import util

from typing import Optional, Dict, Any, List

scriptPath = Path(__file__).resolve().parent

def get_bdfs() -> List[str]:
    pLspci= subprocess.Popen(['lspci'], stdout=subprocess.PIPE)
    pGrep = subprocess.Popen(['grep', '-i', 'xilinx'], stdin=pLspci.stdout, stdout=subprocess.PIPE)
    if pLspci.stdout is not None:
        pLspci.stdout.close()

    sout, serr = pGrep.communicate()

    eSout = sout.decode('utf-8') if sout is not None else ""
    eSerr = serr.decode('utf-8') if serr is not None else ""

    if pGrep.returncode != 0:
        sys.exit(f":ERROR: It failed with stdout: {eSout} stderr: {eSerr}")

    outputLines = eSout.splitlines()
    bdfs = [ i[:7] for i in outputLines if len(i.strip()) >= 0]
    return bdfs

def call_fpga_util(args: List[str]) -> None:
    progScript = scriptPath / 'fpga-util.py'
    assert progScript.exists(), f"Unable to find {progScript}"
    pProg = subprocess.Popen(
        [str(progScript.resolve().absolute())] + args,
        stdout=subprocess.PIPE
    )

    sout, serr = pProg.communicate()

    eSout = sout.decode('utf-8') if sout is not None else ""
    eSerr = serr.decode('utf-8') if serr is not None else ""

    if pProg.returncode != 0:
        sys.exit(f":ERROR: It failed with stdout: {eSout} stderr: {eSerr}")

def disconnect_bdf(bdf: str, vivado: str, hw_server: str) -> None:
    print(f":INFO: Disconnecting BDF: {bdf}")
    call_fpga_util([
        "--bdf", bdf,
        "--disconnect-bdf",
        "--vivado-bin", vivado,
        "--hw-server-bin", hw_server,
    ])

def reconnect_bdf(bdf: str, vivado: str, hw_server: str) -> None:
    print(f":INFO: Reconnecting BDF: {bdf}")
    call_fpga_util([
        "--bdf", bdf,
        "--reconnect-bdf",
        "--vivado-bin", vivado,
        "--hw-server-bin", hw_server,
    ])

def program_fpga(serial: str, bitstream: str, vivado: str, hw_server: str) -> None:
    print(f":INFO: Programming {serial} with {bitstream}")
    call_fpga_util([
        "--serial", serial,
        "--bitstream", bitstream,
        "--vivado-bin", vivado,
        "--hw-server-bin", hw_server,
    ])

def get_serial_numbers_and_fpga_types(vivado: Path) -> Dict[str, str]:
    tclScript = scriptPath / 'get_serial_dev_for_fpgas.tcl'
    assert tclScript.exists(), f"Unable to find {tclScript}"
    rc, stdout, stderr = util.call_vivado(vivado, ['-source', str(tclScript)])
    if rc != 0:
        sys.exit(f":ERROR: It failed with:\nstdout:\n{stdout}\nstderr:\n{stderr}")

    outputLines = stdout.splitlines()
    relevantLines= [s for s in outputLines if ("hw_dev" in s) or ("hw_uid" in s)]
    devs = []
    uids = []

    for line in relevantLines:
        m = re.match(r"^hw_dev: (.*)$", line)
        if m:
            devs.append(m.group(1))

        m = re.match(r"^hw_uid: (.*)$", line)
        if m:
            uids.append(m.group(1))

    uid2dev = {}
    for uid, dev in zip(uids, devs):
        uid2dev[uid] = dev

    return uid2dev

def call_driver(bdf: str, driver: Path, args: List[str]) -> int:
    bus_id = pcielib.get_bus_id_from_extended_bdf(pcielib.get_extended_bdf_from_bdf(bdf))

    driverPath = driver.resolve().absolute()
    assert driverPath.exists(), f"Unable to find {driverPath}"

    pProg = subprocess.Popen(
        [
            str(driverPath),
            "+permissive",
            f"+bus={bus_id}",
        ] + args + [
            "+permissive-off",
            "+prog0=none",
        ],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )

    try:
        sout, serr = pProg.communicate(timeout=5)
    except:
        # spam any amount of flush signals
        pProg.send_signal(signal.SIGPIPE)
        pProg.send_signal(signal.SIGUSR1)
        pProg.send_signal(signal.SIGUSR2)

        # spam any amount of kill signals
        pProg.kill()
        pProg.send_signal(signal.SIGINT)
        pProg.send_signal(signal.SIGTERM)

        # retrieve flushed output
        sout, serr = pProg.communicate()

    eSout = sout.decode('utf-8') if sout is not None else ""
    eSerr = serr.decode('utf-8') if serr is not None else ""

    if pProg.returncode == 124 or pProg.returncode is None:
        sys.exit(":ERROR: Timed out...")
    elif pProg.returncode != 0:
        print(f":WARNING: Running the driver failed...", file=sys.stderr)

    print(f":DEBUG: bdf: {bdf} bus_id: {bus_id}\nstdout:\n{eSout}\nstderr:\n{eSerr}")
    return pProg.returncode

def run_driver_check_fingerprint(bdf: str, driver: Path) -> int:
    print(f":INFO: Running check fingerprint driver call with {bdf}")
    return call_driver(bdf, driver, ["+check-fingerprint"])

def run_driver_write_fingerprint(bdf: str, driver: Path, write_val: int) -> int:
    print(f":INFO: Running write fingerprint driver call with {bdf}")
    # TODO: maybe confirm write went through in the stdout/err?
    return call_driver(bdf, driver, [f"+write-fingerprint={write_val}"])

def main(args: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Generate a FireSim json database file")
    parser.add_argument("--bitstream", help="Bitstream to flash on all Xilinx XDMA-enabled FPGAs (must align with --driver)", type=Path, required=True)
    parser.add_argument("--driver", help="FireSim driver to test bitstream with (must align with --bitstream)", type=Path, required=True)
    parser.add_argument("--out-db-json", help="Path to output FireSim database", type=Path, required=True)
    parser.add_argument("--vivado-bin", help="Explicit path to 'vivado'", type=Path)
    parser.add_argument("--hw-server-bin", help="Explicit path to 'hw_server'", type=Path)
    parsed_args = parser.parse_args(args)

    if parsed_args.hw_server_bin is None:
        parsed_args.hw_server_bin = shutil.which('hw_server')
    if parsed_args.vivado_bin is None:
        parsed_args.vivado_bin = shutil.which('vivado')
    if parsed_args.vivado_bin is None:
        parsed_args.vivado_bin = shutil.which('vivado_lab')

    if parsed_args.hw_server_bin is None:
        print(':ERROR: Could not find Xilinx Hardware Server!', file=sys.stderr)
        exit(1)
    if parsed_args.vivado_bin is None:
        print(':ERROR: Could not find Xilinx Vivado!', file=sys.stderr)
        exit(1)

    parsed_args.vivado_bin = Path(parsed_args.vivado_bin).absolute()
    parsed_args.hw_server_bin = Path(parsed_args.hw_server_bin).absolute()

    eUserId = os.geteuid()
    sudoUserId = os.getenv('SUDO_UID')
    isAdmin = (eUserId == 0) and (sudoUserId is None)
    userId = eUserId if sudoUserId is None else int(sudoUserId)

    if eUserId != 0:
        execvArgs  = ['/usr/bin/sudo', str(Path(__file__).absolute())] + sys.argv[1:]
        execvArgs += ['--vivado-bin', str(parsed_args.vivado_bin), '--hw-server-bin', str(parsed_args.hw_server_bin)]
        print(f":INFO: Running: {execvArgs}")
        os.execv(execvArgs[0], execvArgs)

    print(":INFO: This script expects that all Xilinx XDMA-enabled FPGAs are programmed with the same --bitstream arg. by default (through an MCS file for bistream file)")

    # 1. get all serial numbers for all fpgas on the system

    sno2fpga = get_serial_numbers_and_fpga_types(parsed_args.vivado_bin)
    serials = sno2fpga.keys()
    bdfs = get_bdfs()

    # 2. program all fpgas so that they are in a known state

    # disconnect all
    for bdf in bdfs:
        disconnect_bdf(bdf, str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

    for serial in serials:
        program_fpga(serial, str(parsed_args.bitstream.resolve().absolute()), str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

    # reconnect all
    for bdf in bdfs:
        reconnect_bdf(bdf, str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

    serial2BDF: Dict[str, str] = {}

    write_val = 0xDEADBEEF

    # 3. write to all fingerprints based on bdfs

    for bdf in bdfs:
        run_driver_write_fingerprint(bdf, parsed_args.driver, write_val)

    # 4. create mapping by checking if fingerprint was overridden

    for serial in serials:
        # disconnect all
        for bdf in bdfs:
            disconnect_bdf(bdf, str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

        program_fpga(serial, str(parsed_args.bitstream.resolve().absolute()), str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

        # reconnect all
        for bdf in bdfs:
            reconnect_bdf(bdf, str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

        # read all fingerprints to find the good one
        for bdf in bdfs:
            if not (bdf in serial2BDF.values()):
                rc = run_driver_check_fingerprint(bdf, parsed_args.driver)
                if rc == 0:
                    serial2BDF[serial] = bdf
                    break

        if not (serial in serial2BDF):
            print(f":ERROR: Unable to determine BDF for {serial} FPGA. Something went wrong", file=sys.stderr)
            sys.exit(1)

    print(f":INFO: Mapping: {serial2BDF}")

    finalMap = []
    for s, b in serial2BDF.items():
        finalMap.append({
            "uid" : s,
            "device" : sno2fpga[s],
            "bdf" : b
        })

    with open(parsed_args.out_db_json, 'w') as f:
        json.dump(finalMap, f, indent=2)

    print(f":INFO: Successfully wrote to {parsed_args.out_db_json}")

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
