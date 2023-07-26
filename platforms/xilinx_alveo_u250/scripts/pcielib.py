import subprocess
import sys
import re
import time
from pathlib import Path

from typing import Dict, List

pciDevicesPath = Path('/sys/bus/pci/devices')

def get_device_paths(bus_id: str) -> List[Path]:
    result = []
    for entry in pciDevicesPath.iterdir():
        if re.match('^0000:' + re.escape(bus_id) + ':[a-fA-F0-9]{2}\.[0-7]$', entry.name):
            result.append(entry)
    return result

def get_device_extended_bdfs(bus_id: str) -> List[str]:
    return [e.name for e in get_device_paths(bus_id)]

def get_singular_device_path(bus_id: str) -> Path:
    devicePaths = get_device_paths(bus_id)
    if len(devicePaths) == 0:
        sys.exit(f":ERROR: Unable to obtain Extended Device BDF path for {bus_id}")
    if len(devicePaths) != 1:
        sys.exit(f":ERROR: Unable to obtain Extended Device BDF path for {bus_id} since too many Extended Device BDFs match: {devicePaths}")
    return devicePaths[0]

def get_singular_device_extended_bdf(bus_id: str) -> str:
    deviceBDFs = get_device_extended_bdfs(bus_id)
    if len(deviceBDFs) == 0:
        sys.exit(f":ERROR: Unable to obtain Extended Device BDF for {bus_id}")
    if len(deviceBDFs) != 1:
        sys.exit(f":ERROR: Unable to obtain Extended Device BDF for {bus_id} since too many Extended Device BDFs match: {deviceBDFs}")
    return deviceBDFs[0]

# obtain bridge paths/bdfs

def get_bridge_paths(bus_id: str) -> List[Path]:
    return [e.resolve().absolute().parent for e in get_device_paths(bus_id)]

def get_bridge_extended_bdfs(bus_id: str) -> List[str]:
    return [e.name for e in get_bridge_paths(bus_id)]

def get_singular_bridge_path(bus_id: str) -> Path:
    bridgePaths = get_bridge_paths(bus_id)
    if len(bridgePaths) == 0:
        sys.exit(f":ERROR: Unable to obtain Extended Bridge BDF path for {bus_id}")
    if len(bridgePaths) != 1:
        sys.exit(f":ERROR: Unable to obtain Extended Bridge BDF path for {bus_id} since too many Extended Bridge BDFs match: {bridgePaths}")
    return bridgePaths[0]

def get_singular_bridge_extended_bdf(bus_id: str) -> str:
    bridgeBDFs = get_bridge_extended_bdfs(bus_id)
    if len(bridgeBDFs) == 0:
        sys.exit(f":ERROR: Unable to obtain Extended Bridge BDF for {bus_id}")
    if len(bridgeBDFs) != 1:
        sys.exit(f":ERROR: Unable to obtain Extended Bridge BDF for {bus_id} since too many Extended Bridge BDFs match: {bridgeBDFs}")
    return bridgeBDFs[0]

# misc

def get_fpga_devs(bus_id) -> List[Path]:
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
    fpgaDevices = get_device_extended_bdfs(bus_id)
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
def clear_serr_bits(bus_id: str) -> None:
    for bridgeBDF in get_bridge_extended_bdfs(bus_id):
        run = subprocess.run(['setpci', '-s', bridgeBDF, 'COMMAND=0000:0100'])
        if run.returncode != 0:
            sys.exit(f":ERROR: Unable to clear SERR bit for {bridgeBDF}")
        time.sleep(1)

# clear fatal error reporting enable bit in the device control register
# https://support.xilinx.com/s/question/0D52E00006hpjPHSAY/dell-r720-poweredge-server-reboots-on-fpga-reprogramming?language=en_US
def clear_fatal_error_reporting_bits(bus_id: str) -> None:
    for bridgeBDF in get_bridge_extended_bdfs(bus_id):
        run = subprocess.run(['setpci', '-s', bridgeBDF, 'CAP_EXP+8.w=0000:0004'])
        if run.returncode != 0:
            sys.exit(f":ERROR: Unable to clear error reporting bit for {bridgeBDF}")
        time.sleep(1)

def write_to_linux_device_path(path: Path, data: str = '1\n') -> None:
    try:
        print(f":INFO: Writing to {path}: {data.strip()}")
        open(path, 'w').write(data)
    except:
        sys.exit(f":ERROR: Cannot write to {path} value: {data}")
    time.sleep(1)

def remove(bus_id: str) -> None:
    for devicePaths in get_device_paths(bus_id):
        removePath = devicePaths.resolve().absolute() / 'remove'
        if removePath.exists():
            write_to_linux_device_path(removePath)

def rescan(bus_id: str) -> None:
    for bridgePath in get_bridge_paths(bus_id):
        rescanPath = bridgePath / 'rescan'
        if rescanPath.exists():
            write_to_linux_device_path(rescanPath)
    write_to_linux_device_path(Path('/sys/bus/pci/rescan'))

# enable memory mapped transfers for the fpga
# https://support.xilinx.com/s/question/0D52E00006iHlNoSAK/lspci-reports-bar-0-disabled?language=en_US
def enable_memmapped_transfers(bus_id: str) -> None:
    for deviceBDF in get_device_extended_bdfs(bus_id):
        run = subprocess.run(['setpci', '-s', deviceBDF, 'COMMAND=0x02'])
        if run.returncode != 0:
            sys.exit(f":ERROR: Unable to enable memmapped transfers on {deviceBDF}")
        time.sleep(1)

def any_device_exists(bus_id: str) -> bool:
    return len(get_device_paths(bus_id)) > 0

# converter funcs

def get_extended_bdf_from_bdf(bdf: str) -> str:
    return '0000:' + bdf

def get_bus_id_from_extended_bdf(extended_bdf: str) -> str:
    return extended_bdf[5:7]

def get_bdf_from_extended_bdf(extended_bdf: str) -> str:
    return extended_bdf[5:]
