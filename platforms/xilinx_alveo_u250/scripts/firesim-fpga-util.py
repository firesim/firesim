#!/usr/bin/env python3

import argparse
import os
import subprocess
import sys
import shutil
import json
import tempfile
from pathlib import Path
import pcielib
import util

from typing import Dict, Any, List, Tuple

scriptPath = Path(__file__).resolve().parent
# firesim specific location of where to read/write database file
dbPath = Path() # must be overridden by cmdline

def program_fpga(vivado: Path, serial: str, bitstream: str) -> None:
    progTcl = scriptPath / 'program_fpga.tcl'
    assert progTcl.exists(), f"Unable to find {progTcl}"
    rc, stdout, stderr = util.call_vivado(
        vivado,
        [
            '-source', str(progTcl),
            '-tclargs',
                '-serial', serial,
                '-bitstream_path', bitstream,
        ]
    )
    if rc != 0:
        sys.exit(f":ERROR: Unable to flash FPGA {serial} with {bitstream}.\nstdout:\n{stdout}\nstderr:\n{stderr}")

def program_fpgas(vivado: Path, serial2bitstream: List[Tuple[str, str, Path]]) -> None:
    """Program several FPGAs, given as (serial, JTAG device, bitstream), in one Vivado session (avoids per-FPGA Vivado startup)."""
    fleetTcl = scriptPath / 'program_fpga_fleet.tcl'
    assert fleetTcl.exists(), f"Unable to find {fleetTcl}"
    with tempfile.TemporaryDirectory(prefix='firesim-fpga-util-') as tmpDir:
        mapFile = Path(tmpDir) / 'serial_map.txt'
        mapFile.write_text("".join(f"{s} {d} {b}\n" for s, d, b in serial2bitstream))
        rc, stdout, stderr = util.call_vivado(
            vivado,
            [
                '-source', str(fleetTcl),
                '-tclargs',
                    '-map_file', str(mapFile),
            ]
        )
    if rc != 0:
        sys.exit(f":ERROR: Unable to flash FPGAs {serial2bitstream}.\nstdout:\n{stdout}\nstderr:\n{stderr}")

def read_bitstream_map(mapFile: Path) -> List[Tuple[str, Path]]:
    """Parse '<bdf> <bitstream>' lines into (bus id, bitstream) pairs."""
    entries = []
    for line in mapFile.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        bdf, bitstream = line.split(maxsplit=1)
        bitstream = Path(bitstream.strip()).absolute()
        if not bitstream.is_file():
            sys.exit(f":ERROR: Invalid bitstream: {bitstream}")
        entries.append((pcielib.get_bus_id_from_extended_bdf(pcielib.get_extended_bdf_from_bdf(bdf)), bitstream))
    if not entries:
        sys.exit(f":ERROR: No entries in bitstream map {mapFile}")
    return entries

# mapping functions

def set_fpga_db(db: Path) -> None:
    global dbPath
    dbPath = db

def get_fpga_db() -> Dict[Any, Any]:
    global dbPath
    if dbPath.exists():
        with open(dbPath, 'r') as f:
            return json.load(f)
    else:
        print(f":ERROR: Unable to open {dbPath}. Does it exist? Did you run 'firesim enumeratefpgas'?", file=sys.stderr)
    sys.exit(f":ERROR: Unable to create FPGA database from {dbPath}")

def get_serial_from_bus_id(id: str) -> str:
    deviceBDF = pcielib.get_bdf_from_extended_bdf(pcielib.get_singular_device_extended_bdf(id))
    for e in get_fpga_db():
        if deviceBDF == e['bdf']:
            return e['uid']
    sys.exit(":ERROR: Unable to get serial number from bus id")

def get_device_from_serial(serial: str) -> str:
    """JTAG device (e.g. 'xcvu19p_0') recorded in the FPGA database for a serial number."""
    for e in get_fpga_db():
        if serial == e['uid']:
            return e['device']
    sys.exit(f":ERROR: Unable to find {serial} in the FPGA database")

def get_serials() -> List[str]:
    serials = []
    for e in get_fpga_db():
        serials.append(e['uid'])
    return serials

def get_extended_bdfs() -> List[str]:
    bdfs = []
    for e in get_fpga_db():
        bdfs.append(pcielib.get_extended_bdf_from_bdf(e['bdfs']))
    return bdfs

# main

def main(args: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Program/manipulate a Xilinx XDMA-enabled FPGA device")
    megroup = parser.add_mutually_exclusive_group()
    megroup.add_argument("--bus_id", help="Bus number of FPGA (i.e. ****:<THIS>:**.*)")
    megroup.add_argument("--bdf", help="BDF of FPGA (i.e. ****:<THIS>)")
    megroup.add_argument("--extended-bdf", help="Extended BDF of FPGA (i.e. all of this - ****:**:**.*)")
    megroup.add_argument("--serial", help="Serial number of FPGA (i.e. what 'get_hw_target' shows in Vivado)")
    megroup.add_argument("--all-serials", help="Use all serial numbers (no PCI-E manipulation)", action="store_true")
    megroup.add_argument("--all-bdfs", help="Use all BDFs (PCI-E manipulation)", action="store_true")
    parser.add_argument("--vivado-bin", help="Explicit path to 'vivado'", type=Path)
    parser.add_argument("--hw-server-bin", help="Explicit path to 'hw_server'", type=Path)
    parser.add_argument("--fpga-db", help="Explicit path to FPGA DB file (used to resolve BDFs to serial numbers or obtain all serial numbers of FPGAs)", type=Path, required=True)
    megroup2 = parser.add_mutually_exclusive_group(required=True)
    megroup2.add_argument("--bitstream", help="The bitstream to flash onto FPGA(s)", type=Path)
    megroup2.add_argument("--disconnect-bdf", help="Disconnect BDF(s)", action="store_true")
    megroup2.add_argument("--reconnect-bdf", help="Reconnect BDF(s)", action="store_true")
    megroup2.add_argument("--bitstream-map", help="File of '<bdf> <bitstream>' lines: disconnect, program in one Vivado session, and reconnect those BDFs", type=Path)
    parsed_args = parser.parse_args(args)

    has_target_arg = any([parsed_args.bus_id, parsed_args.bdf, parsed_args.extended_bdf, parsed_args.serial, parsed_args.all_serials, parsed_args.all_bdfs])
    if parsed_args.bitstream_map is not None and has_target_arg:
        parser.error("--bitstream-map selects its own FPGAs; do not combine it with a BDF/serial argument")
    if parsed_args.bitstream_map is None and not has_target_arg:
        parser.error("one of the BDF/serial arguments is required")

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
        print(':ERROR: Could not find Xilinx Vivado (or Vivado Lab)!', file=sys.stderr)
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
        print(f":INFO: Running: {execvArgs}")
        os.execv(execvArgs[0], execvArgs)

    # use cmdline db file
    set_fpga_db(parsed_args.fpga_db)

    def is_bdf_arg(parsed_args) -> bool:
        return parsed_args.bus_id or parsed_args.bdf or parsed_args.extended_bdf or parsed_args.all_bdfs

    def get_bus_ids_from_args(parsed_args) -> List[str]:
        bus_ids = []
        if parsed_args.bus_id:
            bus_ids.append(parsed_args.bus_id)
        if parsed_args.bdf:
            bus_ids.append(pcielib.get_bus_id_from_extended_bdf(pcielib.get_extended_bdf_from_bdf(parsed_args.bdf)))
        if parsed_args.extended_bdf:
            bus_ids.append(pcielib.get_bus_id_from_extended_bdf(parsed_args.extended_bdf))
        if parsed_args.all_bdfs:
            bus_ids.extend([pcielib.get_bus_id_from_extended_bdf(bdf) for bdf in get_extended_bdfs()])
        return bus_ids

    def disconnect_bus_id(bus_id: str) -> None:
        pcielib.clear_serr_bits(bus_id)
        pcielib.clear_fatal_error_reporting_bits(bus_id)
        pcielib.remove(bus_id)
        assert not pcielib.any_device_exists(bus_id), f"{bus_id} still visible. Check for proper removal."

    def reconnect_bus_id(bus_id: str) -> None:
        pcielib.rescan(bus_id)
        pcielib.enable_memmapped_transfers(bus_id)
        assert pcielib.any_device_exists(bus_id), f"{bus_id} not visible. Check for proper rescan."

    # program based on bitstream
    if parsed_args.bitstream is not None:
        if not parsed_args.bitstream.is_file() or not parsed_args.bitstream.exists():
            sys.exit(f":ERROR: Invalid bitstream: {parsed_args.bitstream}")
        else:
            parsed_args.bitstream = parsed_args.bitstream.absolute()

        if is_bdf_arg(parsed_args):
            bus_ids = get_bus_ids_from_args(parsed_args)

            # must be called before the remove otherwise it will not find a serial number
            serialNums = []
            for bus_id in bus_ids:
                serialNums.append(get_serial_from_bus_id(bus_id))

            for bus_id in bus_ids:
                disconnect_bus_id(bus_id)

            # program fpga(s) separately if doing multiple bdfs
            for i, bus_id in enumerate(bus_ids):
                serialNumber = serialNums[i]
                program_fpga(parsed_args.vivado_bin, serialNumber, parsed_args.bitstream)
                print(f":INFO: Successfully programmed FPGA {bus_id} with {parsed_args.bitstream}")

            for bus_id in bus_ids:
                reconnect_bus_id(bus_id)

        if parsed_args.serial or parsed_args.all_serials:
            serials = []
            if parsed_args.serial:
                serials.append(parsed_args.serial)
            if parsed_args.all_serials:
                serials.extend(get_serials())

            for serial in serials:
                program_fpga(parsed_args.vivado_bin, serial, parsed_args.bitstream)
                print(f":INFO: Successfully programmed FPGA {serial} with {parsed_args.bitstream}")
            print(":WARNING: Please warm reboot the machine")

    # program a set of bdfs, each with its own bitstream
    if parsed_args.bitstream_map is not None:
        entries = read_bitstream_map(parsed_args.bitstream_map)

        # must be called before the remove otherwise it will not find a serial number
        serial2bitstream = []
        for bus_id, bit in entries:
            serial = get_serial_from_bus_id(bus_id)
            serial2bitstream.append((serial, get_device_from_serial(serial), bit))

        # always reconnect, so a failed program does not leave FPGAs off the PCI-E bus
        removed = []
        try:
            for bus_id, _ in entries:
                removed.append(bus_id)
                disconnect_bus_id(bus_id)
            program_fpgas(parsed_args.vivado_bin, serial2bitstream)
            print(f":INFO: Successfully programmed FPGAs {serial2bitstream}")
        finally:
            failed = []
            for bus_id in removed:
                try:
                    reconnect_bus_id(bus_id)
                except AssertionError as e:
                    print(f":ERROR: {e}", file=sys.stderr)
                    failed.append(bus_id)
            if failed:
                sys.exit(f":ERROR: Unable to reconnect {failed}")

    # disconnect bdfs
    if parsed_args.disconnect_bdf:
        if is_bdf_arg(parsed_args):
            bus_ids = get_bus_ids_from_args(parsed_args)
            for bus_id in bus_ids:
                disconnect_bus_id(bus_id)
        else:
            sys.exit("Must provide a BDF-like argument to disconnect")

    # reconnect bdfs
    if parsed_args.reconnect_bdf:
        if is_bdf_arg(parsed_args):
            bus_ids = get_bus_ids_from_args(parsed_args)
            for bus_id in bus_ids:
                reconnect_bus_id(bus_id)
        else:
            sys.exit("Must provide a BDF-like argument to disconnect")

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
