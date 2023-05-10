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

from typing import Optional, Dict, Any, List

scriptPath = Path(__file__).resolve().parent

def get_bdfs() -> List[str]:
    pLspci= subprocess.Popen(['lspci'], stdout=subprocess.PIPE)
    pGrep = subprocess.Popen(['grep', '-i', 'serial.*xilinx'], stdin=pLspci.stdout, stdout=subprocess.PIPE)
    if pLspci.stdout is not None:
        pLspci.stdout.close()

    sout, serr = pGrep.communicate()

    if pGrep.returncode != 0:
        print(f":ERROR: It failed with stdout: {sout.decode('utf-8')} stderr: {serr.decode('utf-8')}", file=sys.stderr)
        sys.exit(1)

    outputLines = sout.decode('utf-8').splitlines()
    bdfs = [ i[:7] for i in outputLines if len(i.strip()) >= 0]
    return bdfs

def disconnect_bdf(bdf: str, vivado: str, hw_server: str) -> None:
    print(f"Disconnecting BDF: {bdf}")
    progScript = scriptPath / 'program_fpga.py'
    assert progScript.exists()
    pProg = subprocess.Popen(
        [
            str(progScript),
            "--bdf", bdf,
            "--disconnect-bdf",
            "--vivado-bin", vivado,
            "--hw-server-bin", hw_server,
        ],
        stdout=subprocess.PIPE
    )

    sout, serr = pProg.communicate()

    if pProg.returncode != 0:
        print(f":ERROR: It failed with stdout: {sout.decode('utf-8')} stderr: {serr.decode('utf-8')}", file=sys.stderr)
        sys.exit(1)

def reconnect_bdf(bdf: str, vivado: str, hw_server: str) -> None:
    print(f"Reconnecting BDF: {bdf}")
    progScript = scriptPath / 'program_fpga.py'
    assert progScript.exists()
    pProg = subprocess.Popen(
        [
            str(progScript),
            "--bdf", bdf,
            "--reconnect-bdf",
            "--vivado-bin", vivado,
            "--hw-server-bin", hw_server,
        ],
        stdout=subprocess.PIPE
    )

    sout, serr = pProg.communicate()

    if pProg.returncode != 0:
        print(f":ERROR: It failed with stdout: {sout.decode('utf-8')} stderr: {serr.decode('utf-8')}", file=sys.stderr)
        sys.exit(1)

def program_fpga(serial: str, bitstream: str, vivado: str, hw_server: str) -> None:
    print(f"Programming {serial} with {bitstream}")
    progScript = scriptPath / 'program_fpga.py'
    assert progScript.exists()
    pProg = subprocess.Popen(
        [
            str(progScript),
            "--serial_no", serial,
            "--bitstream", bitstream,
            "--vivado-bin", vivado,
            "--hw-server-bin", hw_server,
        ],
        stdout=subprocess.PIPE
    )

    sout, serr = pProg.communicate()

    if pProg.returncode != 0:
        print(f":ERROR: It failed with stdout: {sout.decode('utf-8')} stderr: {serr.decode('utf-8')}", file=sys.stderr)
        sys.exit(1)


def get_serial_numbers_and_fpga_types(vivado: str) -> Dict[str, str]:
    tclScript = scriptPath / 'get_serial_dev_for_fpgas.tcl'
    assert tclScript.exists()
    pVivado = subprocess.Popen(
        [
            vivado,
            '-mode', 'tcl',
            '-nolog', '-nojournal', '-notrace',
            '-source', str(tclScript),
        ],
        stdout=subprocess.PIPE
    )

    sout, serr = pVivado.communicate()

    if pVivado.returncode != 0:
        print(f":ERROR: It failed with stdout: {sout.decode('utf-8')} stderr: {serr.decode('utf-8')}", file=sys.stderr)
        sys.exit(1)

    outputLines = sout.decode('utf-8').splitlines()
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

def run_driver_check_fingerprint(bdf: str, driver: Path, write_val: int) -> int:
    bus_id = bdf[:2]
    print(f"Running check fingerprint driver call with {bus_id} corresponding to {bdf}")

    driverPath = driver.resolve().absolute()
    assert driverPath.exists()

    cmd = [
        str(driverPath),
        "+permissive",
        f"+slotid={bus_id}",
        "+check-fingerprint",
        "+permissive-off",
        "+prog0=none",
    ]
    pProg = subprocess.Popen(
        cmd,
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

    stdout = sout.decode('utf-8').splitlines()

    if pProg.returncode == 124 or pProg.returncode is None:
        print(":ERROR: Timed out...", file=sys.stderr)
        sys.exit(1)
    elif pProg.returncode != 0:
        print(f":WARNING: Running the driver failed...", file=sys.stderr)
        print(f":DEBUG: bdf: {bdf} bus_id: {bus_id} stdout: {stdout}", file=sys.stderr)
        return pProg.returncode

    # successfully read fingerprint
    print(f":DEBUG: bdf: {bdf} bus_id: {bus_id} stdout: {stdout}", file=sys.stderr)
    return 0

def run_driver_write_fingerprint(bdf: str, driver: Path, write_val: int) -> None:
    bus_id = bdf[:2]
    print(f"Running write fingerprint driver call with {bus_id} corresponding to {bdf}")

    driverPath = driver.resolve().absolute()
    assert driverPath.exists()

    cmd = [
        str(driverPath),
        "+permissive",
        f"+slotid={bus_id}",
        f"+write-fingerprint={write_val}",
        "+permissive-off",
        "+prog0=none",
    ]
    pProg = subprocess.Popen(
        cmd,
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

    stdout = sout.decode('utf-8').splitlines()

    if pProg.returncode == 124 or pProg.returncode is None:
        print(":ERROR: Timed out...", file=sys.stderr)
        print(f":DEBUG: bdf: {bdf} bus_id: {bus_id} stdout: {stdout}", file=sys.stderr)
        sys.exit(1)
    elif pProg.returncode != 0:
        print(f":ERROR: Running the driver failed...", file=sys.stderr)
        print(f":DEBUG: bdf: {bdf} bus_id: {bus_id} stdout: {stdout}", file=sys.stderr)
        sys.exit(1)

    # TODO: Maybe confirm that the write went through?

    print(f":DEBUG: bdf: {bdf} bus_id: {bus_id} stdout: {stdout}", file=sys.stderr)
    return

def main(args: List[str]) -> int:
    parser = argparse.ArgumentParser(description="")
    parser.add_argument("--vivado-bin", help="Explicit path to 'vivado'", type=Path)
    parser.add_argument("--hw-server-bin", help="Explicit path to 'hw_server'", type=Path)
    parser.add_argument("--working-bitstream", help="Bitstream to flash and verify with a driver", type=Path, required=True)
    parser.add_argument("--out-db-json", help="Where to dump the output database mapping", type=Path, required=True)
    parser.add_argument("--driver", help="Driver to test with", type=Path, required=True)
    parsed_args = parser.parse_args(args)

    if parsed_args.hw_server_bin is None:
        parsed_args.hw_server_bin = shutil.which('hw_server')
    if parsed_args.vivado_bin is None:
        parsed_args.vivado_bin = shutil.which('vivado')

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

    # if not sudoer, spawn w/ sudo
    if eUserId != 0:
        execvArgs  = ['/usr/bin/sudo', str(Path(__file__).absolute())] + sys.argv[1:]
        execvArgs += ['--vivado-bin', str(parsed_args.vivado_bin), '--hw-server-bin', str(parsed_args.hw_server_bin)]
        print(f"Running: {execvArgs}")
        os.execv(execvArgs[0], execvArgs)

    # expects that fpgas are programmed with working bitstreams (w/ xdma)

    sno2fpga = get_serial_numbers_and_fpga_types(str(parsed_args.vivado_bin))
    serials = sno2fpga.keys()
    bdfs = get_bdfs()

    serial2BDF: Dict[str, str] = {}

    write_val = 0xDEADBEEF

    # write to all fingerprints based on bdfs
    for bdf in bdfs:
        run_driver_write_fingerprint(bdf, parsed_args.driver, write_val)

    for serial in serials:
        # disconnect all
        for bdf in bdfs:
            disconnect_bdf(bdf, str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

        program_fpga(serial, str(parsed_args.working_bitstream.resolve().absolute()), str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

        # reconnect all
        for bdf in bdfs:
            reconnect_bdf(bdf, str(parsed_args.vivado_bin), str(parsed_args.hw_server_bin))

        # read all fingerprints to find the good one
        for bdf in bdfs:
            if not (bdf in serial2BDF.values()):
                rc = run_driver_check_fingerprint(bdf, parsed_args.driver, write_val)
                if rc == 0:
                    serial2BDF[serial] = bdf
                    break

        if not (serial in serial2BDF):
            print(f":ERROR: Unable to determine BDF for {serial} FPGA. Something went wrong", file=sys.stderr)
            sys.exit(1)

    print(f"Mapping: {serial2BDF}")

    finalMap = []
    for s, b in serial2BDF.items():
        finalMap.append({
            "uid" : s,
            "device" : sno2fpga[s],
            "bdf" : b
        })

    with open(parsed_args.out_db_json, 'w') as f:
        json.dump(finalMap, f, indent=2)

    print(f"Successfully wrote to {parsed_args.out_db_json}")

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
