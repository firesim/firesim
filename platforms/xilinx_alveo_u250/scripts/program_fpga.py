#!/usr/bin/env python3

import argparse
import os
import subprocess
import sys
import pwd
import re
from pathlib import Path

from typing import Optional, Dict, Any, List

pciDevicesPath = Path('/sys/bus/pci/devices')

def get_bridge_bdf(id: str) -> str:
    for entry in pciDevicesPath.iterdir():
        if re.match('^0000:' + re.escape(id) + ':[a-fA-F0-9]{2}\.[0-7]$', entry.name):
            bridgePath = entry.resolve().absolute().parent
            if bridgePath.exists():
                return bridgePath.name
    print(":ERROR: Unable to obtain bridge BDF")
    sys.exit(1)

def get_fpga_bdfs(id: str) -> List[str]:
    result = []
    for entry in pciDevicesPath.iterdir():
        if re.match('^0000:' + re.escape(id) + ':[a-fA-F0-9]{2}\.[0-7]$', entry.name):
            result.append(entry.name)
    return result

def get_fpga_devs(id) -> List[Path]:
    def readUevent(path: Path) -> Dict[Any, Any]:
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
    fpgaDevices = get_fpga_bdfs(id)
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
def clear_serr(id: str) -> None:
    bridgeBDF = get_bridge_bdf(id)
    run = subprocess.run(['setpci', '-s', bridgeBDF, 'COMMAND=0000:0100'])
    if run.returncode != 0:
        print(":ERROR: Unable to clear SERR bit")
        sys.exit(1)


# clear fatal error reporting enable bit in the device control register
# https://support.xilinx.com/s/question/0D52E00006hpjPHSAY/dell-r720-poweredge-server-reboots-on-fpga-reprogramming?language=en_US
def clear_fatal_error_reporting(id: str) -> None:
    bridgeBDF = get_bridge_bdf(id)
    run = subprocess.run(['setpci', '-s', bridgeBDF, 'CAP_EXP+8.w=0000:0004'])
    if run.returncode != 0:
        print(":ERROR: Unable to clear SERR bit")
        sys.exit(1)

def write_to_linux_device_path(path: Path, data: str = '1\n') -> None:
    try:
        open(path, 'w').write(data)
    except:
        print(f":ERROR: Cannot write to {path} value: {data}")
        sys.exit(1)

def remove(id: str) -> None:
    bridgeBDF = get_bridge_bdf(id)
    deviceBDFs = get_fpga_bdfs(id)
    for deviceBDF in deviceBDFs:
        removePath = pciDevicesPath / bridgeBDF / deviceBDF / 'remove'
        if removePath.exists():
            write_to_linux_device_path(removePath)

def rescan(id: str) -> None:
    bridgeBDF = get_bridge_bdf(id)
    if bridgeBDF is not None:
        rescanPath = pciDevicesPath / bridgeBDF / 'rescan'
        write_to_linux_device_path(rescanPath)
    else:
        write_to_linux_device_path('/sys/bus/pci/rescan')

# enable memory mapped transfers for the fpga
# https://support.xilinx.com/s/question/0D52E00006iHlNoSAK/lspci-reports-bar-0-disabled?language=en_US
def enable_memmapped_transfers(id: str) -> None:
    deviceBDFs = get_fpga_bdfs(id)
    for deviceBDF in deviceBDFs:
        run = subprocess.run(['setpci', '-s', deviceBDF, 'COMMAND=0x02'])
        if run.returncode != 0:
            print(f":ERROR: Unable to enable memmapped transfers on {deviceBDF}")
            sys.exit(1)

def program_fpga(serial: str, board: str, bitstream: str) -> None:
    print(":WARNING: This only can target the 1st FPGA on a machine currently...")

    pVivado = subprocess.Popen(
        [
            'vivado',
            '-mode', 'tcl',
            '-nolog', '-nojournal', '-notrace',
            '-source', scriptPath / 'program_fpga.tcl',
            '-tclargs',
                '-board', board,
                '-bitstream_path', bitstream,
        ],
        stdin=subprocess.DEVNULL
    )

    pVivado.wait()

    if pVivado.returncode != 0:
        print(":ERROR: Unable to flash FPGA")
        sys.exit(1)

def get_serial_from_bdf(id: str) -> str:
    deviceBDFs = get_fpga_bdfs(parsed_args.bus_id)
    if len(deviceBDFs) == 0:
        print(f":ERROR: Unable to obtain Extended Device BDF for {parsed_args.bus_id}")
        sys.exit(1)
    return "TODO"

def main(args: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Program a Xilinx XDMA-enabled FPGA")
    megroup = parser.add_mutually_exclusive_group(required=True)
    megroup.add_argument("--bus_id", help="Bus number of FPGA to flash (i.e. ****:<THIS>:**.*)")
    megroup.add_argument("--serial_no", help="Serial number of FPGA to flash (i.e. what 'get_hw_target' shows in Vivado)")
    parser.add_argument("--bitstream", help="Bitstream to flash onto FPGA", required=True, type=Path)
    parser.add_argument("--board", help="FPGA board to flash", required=True)
    parsed_args = parser.parse_args(args)

    scriptPath = Path(__file__).resolve().parent

    eUserId = os.geteuid()
    sudoUserId = os.getenv('SUDO_UID')
    isAdmin = (eUserId == 0) and (sudoUserId is None)
    userId = eUserId if sudoUserId is None else int(sudoUserId)

    if not isAdmin:
        print(":ERROR: Requires running script with 'sudo'")
        sys.exit(1)

    if not parsed_args.bitstream.is_file() or not parsed_args.bitstream.exists():
        print(f":ERROR: Invalid bitstream: {parsed_args.bitstream}")
        sys.exit(1)
    else:
        parsed_args.bitstream = parsed_args.bitstream.absolute()

    if parsed_args.bus_id:
        serialNumber = get_serial_from_bdf(id)
        clear_serr(parsed_args.bus_id)
        clear_fatal_error_reporting(parsed_args.bus_id)
        remove(parsed_args.bus_id)
        program_fpga(serialNumber, parsed_args.board, parsed_args.bitstream)
        rescan(parsed_args.bus_id)
        enable_memmapped_transfers(parsed_args.bus_id)

        print(f"Successfully programmed FPGA {parsed_args.bus_id} with {parsed_args.bitstream}")

    if parsed_args.serial_no:
        program_fpga(parsed_args.serial_no, parsed_args.board, parsed_args.bitstream)

        print(f"Successfully programmed FPGA {parsed_args.serial_no} with {parsed_args.bitstream}")
        print(":WARNING: Please warm reboot the machine")

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
