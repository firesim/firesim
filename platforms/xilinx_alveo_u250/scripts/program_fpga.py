#!/usr/bin/env python3

import argparse
import os
import subprocess
import sys
import pwd
import re
import shutil
import json
from pathlib import Path

from typing import Optional, Dict, Any, List

pciDevicesPath = Path('/sys/bus/pci/devices')
scriptPath = Path(__file__).resolve().parent

# obtain device paths/bdfs

def get_device_paths(id: str) -> List[Path]:
    result = []
    for entry in pciDevicesPath.iterdir():
        if re.match('^0000:' + re.escape(id) + ':[a-fA-F0-9]{2}\.[0-7]$', entry.name):
            result.append(entry)
    return result

def get_device_extended_bdfs(id: str) -> List[str]:
    return [e.name for e in get_device_paths(id)]

def get_singular_device_path(id: str) -> Path:
    devicePaths = get_device_paths(id)
    if len(devicePaths) == 0:
        print(f":ERROR: Unable to obtain Extended Device BDF path for {id}", file=sys.stderr)
        sys.exit(1)
    if len(devicePaths) != 1:
        print(f":ERROR: Unable to obtain Extended Device BDF path for {id} since too many Extended Device BDFs match: {devicePaths}", file=sys.stderr)
        sys.exit(1)
    return devicePaths[0]

def get_singular_device_extended_bdf(id: str) -> str:
    deviceBDFs = get_device_extended_bdfs(id)
    if len(deviceBDFs) == 0:
        print(f":ERROR: Unable to obtain Extended Device BDF for {id}", file=sys.stderr)
        sys.exit(1)
    if len(deviceBDFs) != 1:
        print(f":ERROR: Unable to obtain Extended Device BDF for {id} since too many Extended Device BDFs match: {deviceBDFs}", file=sys.stderr)
        sys.exit(1)
    return deviceBDFs[0]

# obtain bridge paths/bdfs

def get_bridge_paths(id: str) -> List[Path]:
    return [e.resolve().absolute().parent for e in get_device_paths(id)]

def get_bridge_extended_bdfs(id: str) -> List[str]:
    return [e.name for e in get_bridge_paths(id)]

def get_singular_bridge_path(id: str) -> Path:
    bridgePaths = get_bridge_paths(id)
    if len(bridgePaths) == 0:
        print(f":ERROR: Unable to obtain Extended Bridge BDF path for {id}", file=sys.stderr)
        sys.exit(1)
    if len(bridgePaths) != 1:
        print(f":ERROR: Unable to obtain Extended Bridge BDF path for {id} since too many Extended Bridge BDFs match: {bridgePaths}", file=sys.stderr)
        sys.exit(1)
    return bridgePaths[0]

def get_singular_bridge_extended_bdf(id: str) -> str:
    bridgeBDFs = get_bridge_extended_bdfs(id)
    if len(bridgeBDFs) == 0:
        print(f":ERROR: Unable to obtain Extended Bridge BDF for {id}", file=sys.stderr)
        sys.exit(1)
    if len(bridgeBDFs) != 1:
        print(f":ERROR: Unable to obtain Extended Bridge BDF for {id} since too many Extended Bridge BDFs match: {bridgeBDFs}", file=sys.stderr)
        sys.exit(1)
    return bridgeBDFs[0]

# misc

def get_fpga_devs(id) -> List[Path]:
    def readUevent(path: Path) -> Dict[str, str]:
        if not (path / 'uevent').exists():
            return {}
        return { entry[0]: entry[1] for entry in [line.strip('\n\r ').split('=') for line in open(f'{path}/uevent', 'r').readlines()] if len(entry) >= 2 }

    def xdmaResolver(path: Path) -> List[Path]:
        xdmaDevs = []
        for f in ['resource', 'resource0', 'resource1']:
            rsrcPath = (path / f)
            if rsrcPath.exists():
                xdmaDevs.append(rsrcPath)
        xdmaPath = (path / 'xdma')
        if xdmaPath.is_dir():
            ueventEntries = [readUevent(xdmaPath / entry.name) for entry in xdmaPath.iterdir() if (xdmaPath / entry.name).is_dir()]
            xdmaDevs.extend([Path('/dev') / uevent['DEVNAME'] for uevent in ueventEntries if 'DEVNAME' in uevent and (Path('/dev') / uevent['DEVNAME']).exists()])
        return xdmaDevs

    resolvers = {
        'xdma' : xdmaResolver
    }

    returnDevs = []
    fpgaDevices = get_device_extended_bdfs(id)
    for fpgaDev in fpgaDevices:
        path = pciDevicesPath / fpgaDev
        fpgaDevUevent = readUevent(path)
        if 'DRIVER' not in fpgaDevUevent:
            print(":WARNING: Verify that 'xdma' driver is loaded")
            continue
        if fpgaDevUevent['DRIVER'] not in resolvers:
            continue
        returnDevs.extend(resolvers[fpgaDevUevent['DRIVER']](path.resolve()))

    return returnDevs

# clear SERR bit in command register
# https://support.xilinx.com/s/question/0D52E00006hpjPHSAY/dell-r720-poweredge-server-reboots-on-fpga-reprogramming?language=en_US
def clear_serr_bits(id: str) -> None:
    for bridgeBDF in get_bridge_extended_bdfs(id):
        run = subprocess.run(['setpci', '-s', bridgeBDF, 'COMMAND=0000:0100'])
        if run.returncode != 0:
            print(f":ERROR: Unable to clear SERR bit for {bridgeBDF}", file=sys.stderr)
            sys.exit(1)

# clear fatal error reporting enable bit in the device control register
# https://support.xilinx.com/s/question/0D52E00006hpjPHSAY/dell-r720-poweredge-server-reboots-on-fpga-reprogramming?language=en_US
def clear_fatal_error_reporting_bits(id: str) -> None:
    for bridgeBDF in get_bridge_extended_bdfs(id):
        run = subprocess.run(['setpci', '-s', bridgeBDF, 'CAP_EXP+8.w=0000:0004'])
        if run.returncode != 0:
            print(f":ERROR: Unable to clear error reporting bit for {bridgeBDF}", file=sys.stderr)
            sys.exit(1)

def write_to_linux_device_path(path: Path, data: str = '1\n') -> None:
    try:
        print(f"Writing to {path}: {data.strip()}")
        open(path, 'w').write(data)
    except:
        print(f":ERROR: Cannot write to {path} value: {data}", file=sys.stderr)
        sys.exit(1)

def remove(id: str) -> None:
    for devicePaths in get_device_paths(id):
        removePath = devicePaths.resolve().absolute() / 'remove'
        if removePath.exists():
            write_to_linux_device_path(removePath)

def rescan(id: str) -> None:
    for bridgePath in get_bridge_paths(id):
        rescanPath = bridgePath / 'rescan'
        if rescanPath.exists():
            write_to_linux_device_path(rescanPath)
    write_to_linux_device_path(Path('/sys/bus/pci/rescan'))

# enable memory mapped transfers for the fpga
# https://support.xilinx.com/s/question/0D52E00006iHlNoSAK/lspci-reports-bar-0-disabled?language=en_US
def enable_memmapped_transfers(id: str) -> None:
    for deviceBDF in get_device_extended_bdfs(id):
        run = subprocess.run(['setpci', '-s', deviceBDF, 'COMMAND=0x02'])
        if run.returncode != 0:
            print(f":ERROR: Unable to enable memmapped transfers on {deviceBDF}", file=sys.stderr)
            sys.exit(1)

def program_fpga(vivado: str, serial: str, bitstream: str) -> None:
    pVivado = subprocess.Popen(
        [
            vivado,
            '-mode', 'tcl',
            '-nolog', '-nojournal', '-notrace',
            '-source', scriptPath / 'program_fpga.tcl',
            '-tclargs',
                '-serial', serial,
                '-bitstream_path', bitstream,
        ],
        stdin=subprocess.DEVNULL
    )

    pVivado.wait()

    if pVivado.returncode != 0:
        print(f":ERROR: Unable to flash FPGA {serial} with {bitstream}", file=sys.stderr)
        sys.exit(1)

# mapping functions

def get_fpga_db() -> Dict[Any, Any]:
    db_file = Path("/opt/firesim-db.json")
    if db_file.exists():
        with open(db_file, 'r') as f:
            db = json.load(f)
            return db
    else:
        print(f":ERROR: Unable to open {db_file}. Does it exist? Did you run 'firesim enumeratefpgas'?")

    print(f":ERROR: Unable to create FPGA database from {db_file}", file=sys.stderr)
    sys.exit(1)

def get_serial_from_bus_id(id: str) -> str:
    deviceBDF = get_bdf_from_extended_bdf(get_singular_device_extended_bdf(id))
    db = get_fpga_db()
    for e in db:
        if deviceBDF == e['bdf']:
            return e['uid']
    print(":ERROR: Unable to get serial number from bus id", file=sys.stderr)
    sys.exit(1)

def get_serials() -> List[str]:
    db = get_fpga_db()
    serials = []
    for e in db:
        serials.append(e['uid'])
    return serials

def get_extended_bdfs() -> List[str]:
    db = get_fpga_db()
    bdfs = []
    for e in db:
        bdfs.append(convert_bdf_to_extended_bdf(e['bdfs']))
    return bdfs

def convert_bdf_to_extended_bdf(bdf: str) -> str:
    return '0000:' + bdf

def get_bus_id_from_extended_bdf(extended_bdf: str) -> str:
    return extended_bdf[5:7]

def get_bdf_from_extended_bdf(extended_bdf: str) -> str:
    return extended_bdf[5:]

# main

def main(args: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Program a Xilinx XDMA-enabled FPGA")
    megroup = parser.add_mutually_exclusive_group(required=True)
    megroup.add_argument("--bus_id", help="Bus number of FPGA (i.e. ****:<THIS>:**.*)")
    megroup.add_argument("--bdf", help="BDF of FPGA (i.e. ****:<THIS>)")
    megroup.add_argument("--extended-bdf", help="Extended BDF of FPGA (i.e. all of this - ****:**:**.*)")
    megroup.add_argument("--serial_no", help="Serial number of FPGA (i.e. what 'get_hw_target' shows in Vivado)")
    megroup.add_argument("--all-serials", help="Use all serial numbers (no PCI-E manipulation)", action="store_true")
    megroup.add_argument("--all-bdfs", help="Use all BDFs (PCI-E manipulation)", action="store_true")
    parser.add_argument("--vivado-bin", help="Explicit path to 'vivado'", type=Path)
    parser.add_argument("--hw-server-bin", help="Explicit path to 'hw_server'", type=Path)
    megroup2 = parser.add_mutually_exclusive_group(required=True)
    megroup2.add_argument("--bitstream", help="The bitstream to flash onto FPGA(s)", type=Path)
    megroup2.add_argument("--disconnect-bdf", help="Disconnect BDF(s)", action="store_true")
    megroup2.add_argument("--reconnect-bdf", help="Reconnect BDF(s)", action="store_true")
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

    # program based on bitstream
    if parsed_args.bitstream is not None:
        if not parsed_args.bitstream.is_file() or not parsed_args.bitstream.exists():
            print(f":ERROR: Invalid bitstream: {parsed_args.bitstream}")
            sys.exit(1)
        else:
            parsed_args.bitstream = parsed_args.bitstream.absolute()

        if parsed_args.bus_id or parsed_args.bdf or parsed_args.extended_bdf or parsed_args.all_bdfs:
            bus_ids = []
            if parsed_args.bus_id:
                bus_ids.append(parsed_args.bus_id)
            if parsed_args.bdf:
                bus_ids.append(get_bus_id_from_extended_bdf(convert_bdf_to_extended_bdf(parsed_args.bdf)))
            if parsed_args.extended_bdf:
                bus_ids.append(get_bus_id_from_extended_bdf(parsed_args.extended_bdf))
            if parsed_args.all_bdfs:
                bus_ids.extend([get_bus_id_from_extended_bdf(bdf) for bdf in get_extended_bdfs()])

            # must be called before the remove otherwise it will not find a serial number
            serialNums = []
            for bus_id in bus_ids:
                serialNums.append(get_serial_from_bus_id(bus_id))

            for bus_id in bus_ids:
                clear_serr_bits(bus_id)
                clear_fatal_error_reporting_bits(bus_id)
                remove(bus_id)

            # program fpga(s) separately if doing multiple bdfs
            for i, bus_id in enumerate(bus_ids):
                serialNumber = serialNums[i]
                program_fpga(str(parsed_args.vivado_bin), serialNumber, parsed_args.bitstream)
                print(f"Successfully programmed FPGA {bus_id} with {parsed_args.bitstream}")

            for bus_id in bus_ids:
                rescan(bus_id)
                enable_memmapped_transfers(bus_id)

        if parsed_args.serial_no or parsed_args.all_serials:
            serial_nos = []
            if parsed_args.serial_no:
                serial_nos.append(parsed_args.serial_no)
            if parsed_args.all_serials:
                serial_nos.extend(get_serials())

            for serial in serial_nos:
                program_fpga(str(parsed_args.vivado_bin), serial, parsed_args.bitstream)
                print(f"Successfully programmed FPGA {serial} with {parsed_args.bitstream}")
            print(":WARNING: Please warm reboot the machine")

    # disconnect bdfs
    if parsed_args.disconnect_bdf:
        if parsed_args.bus_id or parsed_args.all_bdfs or parsed_args.bdf or parsed_args.extended_bdf:
            bus_ids = []
            if parsed_args.bus_id:
                bus_ids.append(parsed_args.bus_id)
            if parsed_args.bdf:
                bus_ids.append(get_bus_id_from_extended_bdf(convert_bdf_to_extended_bdf(parsed_args.bdf)))
            if parsed_args.extended_bdf:
                bus_ids.append(get_bus_id_from_extended_bdf(parsed_args.extended_bdf))
            if parsed_args.all_bdfs:
                bus_ids.extend([get_bus_id_from_extended_bdf(bdf) for bdf in get_extended_bdfs()])

            for bus_id in bus_ids:
                clear_serr_bits(bus_id)
                clear_fatal_error_reporting_bits(bus_id)
                remove(bus_id)

    # reconnect bdfs
    if parsed_args.reconnect_bdf:
        if parsed_args.bus_id or parsed_args.all_bdfs or parsed_args.bdf or parsed_args.extended_bdf:
            bus_ids = []
            if parsed_args.bus_id:
                bus_ids.append(parsed_args.bus_id)
            if parsed_args.bdf:
                bus_ids.append(get_bus_id_from_extended_bdf(convert_bdf_to_extended_bdf(parsed_args.bdf)))
            if parsed_args.extended_bdf:
                bus_ids.append(get_bus_id_from_extended_bdf(parsed_args.extended_bdf))
            if parsed_args.all_bdfs:
                bus_ids.extend([get_bus_id_from_extended_bdf(bdf) for bdf in get_extended_bdfs()])

            for bus_id in bus_ids:
                rescan(bus_id)
                enable_memmapped_transfers(bus_id)

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
