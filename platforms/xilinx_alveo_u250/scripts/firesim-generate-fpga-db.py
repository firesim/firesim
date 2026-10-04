#!/usr/bin/env python3

"""Generate the FireSim FPGA database (JTAG serial -> PCI-E BDF mapping).

Every FPGA whose part matches --bitstream is programmed with it and given a
fingerprint over PCI-E; each FPGA is then reprogrammed in turn, and the BDF
whose fingerprint disappears belongs to that JTAG serial. This takes over all
of those FPGAs; other FPGAs on the host (different part, or not running a
FireSim bitstream) are left untouched.

All JTAG work runs in a single Vivado session (enumerate_fpgas.tcl, installed
next to this script), which this script steps through its phases with marker
files in a work directory.
"""

import argparse
import json
import os
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path
import pcielib

from typing import Dict, List, Set

scriptPath = Path(__file__).resolve().parent

# upper bound on programming one FPGA over JTAG (~40 s for a VU19P)
PROGRAM_TIMEOUT_S = 180

# PCI-E ID of the XDMA endpoint in FireSim bitstreams (matches the driver default)
FIRESIM_XILINX_PCI_ID = "10ee:903f"

def get_bdfs() -> List[str]:
    """BDFs of FPGAs currently running a FireSim bitstream; other PCI-E devices are left alone."""
    out = subprocess.run(['lspci', '-d', FIRESIM_XILINX_PCI_ID], stdout=subprocess.PIPE, check=True).stdout.decode('utf-8')
    bdfs = [line[:7] for line in out.splitlines() if line.strip()]
    if not bdfs:
        sys.exit(f":ERROR: No FireSim FPGAs ({FIRESIM_XILINX_PCI_ID}) on PCI-E. FPGAs must boot a FireSim bitstream (e.g. from flash) before enumeration.")
    return bdfs

def get_bitstream_device(bitstream: Path) -> str:
    """Device (e.g. 'xcvu19p') from the part field ('b') of a Xilinx .bit header."""
    with open(bitstream, 'rb') as f:
        data = f.read(1024)
    try:
        off = 2 + struct.unpack('>H', data[:2])[0] + 2
        while True:
            key = chr(data[off])
            if key == 'e':
                break
            n = struct.unpack('>H', data[off + 1:off + 3])[0]
            value = data[off + 3:off + 3 + n - 1].decode('ascii')
            if key == 'b':
                return value.split('-')[0].lower()
            off += 3 + n
    except (IndexError, struct.error, UnicodeDecodeError):
        pass
    sys.exit(f":ERROR: Unable to read the target part from {bitstream}. Is it a Xilinx .bit file?")

def get_bus_id(bdf: str) -> str:
    """Bus id (e.g. '05') of a BDF, as Vivado and the FPGA database report it."""
    return pcielib.get_bus_id_from_extended_bdf(pcielib.get_extended_bdf_from_bdf(bdf))

def disconnect_bdf(bdf: str) -> None:
    print(f":INFO: Disconnecting BDF: {bdf}")
    bus_id = get_bus_id(bdf)
    pcielib.clear_serr_bits(bus_id)
    pcielib.clear_fatal_error_reporting_bits(bus_id)
    pcielib.remove(bus_id)
    assert not pcielib.any_device_exists(bus_id), f"{bus_id} still visible. Check for proper removal."

def reconnect_bdf(bdf: str) -> None:
    print(f":INFO: Reconnecting BDF: {bdf}")
    bus_id = get_bus_id(bdf)
    pcielib.rescan(bus_id)
    pcielib.enable_memmapped_transfers(bus_id)
    assert pcielib.any_device_exists(bus_id), f"{bus_id} not visible. Check for proper rescan."

def call_driver(bdf: str, driver: Path, args: List[str]) -> int:
    bus_id = get_bus_id(bdf)

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

class VivadoSession:
    """A background enumerate_fpgas.tcl run, coordinated through marker files."""

    def __init__(self, vivado: Path, bitstream: Path, device: str) -> None:
        tclScript = scriptPath / 'enumerate_fpgas.tcl'
        assert tclScript.exists(), f"Unable to find {tclScript}"
        self.work_dir = Path(tempfile.mkdtemp(prefix="firesim-enumerate-"))
        self.log = self.work_dir / "vivado.log"
        print(f":INFO: Starting Vivado (log: {self.log})")
        with open(self.log, "w") as logFile:
            self.proc = subprocess.Popen(
                [
                    str(vivado), '-mode', 'batch',
                    '-source', str(tclScript),
                    '-tclargs',
                        '-bit_path', str(bitstream),
                        '-device', device,
                        '-work_dir', str(self.work_dir),
                ],
                stdin=subprocess.DEVNULL,
                stdout=logFile,
                stderr=subprocess.STDOUT,
            )

    def wait(self, name: str, timeout_s: int) -> str:
        """Block until Vivado writes marker 'name' and return its contents; exit if Vivado dies or times out."""
        path = self.work_dir / name
        deadline = time.monotonic() + timeout_s
        while not path.exists():
            if self.proc.poll() is not None:
                sys.exit(f":ERROR: Vivado exited (rc={self.proc.returncode}) before '{name}'. See {self.log}")
            if time.monotonic() > deadline:
                sys.exit(f":ERROR: Timed out after {timeout_s}s waiting for '{name}'. See {self.log}")
            time.sleep(0.5)
        return path.read_text().strip()

    def signal(self, name: str) -> None:
        """Atomically create marker 'name' for the Tcl script to pick up."""
        tmp = self.work_dir / f"{name}.tmp"
        tmp.write_text("\n")
        os.replace(tmp, self.work_dir / name)

    def close(self) -> None:
        """Let Vivado shut down its hw_server; killing it would leave a stale one behind."""
        if self.proc.poll() is None:
            self.signal("abort")
            try:
                self.proc.wait(timeout=PROGRAM_TIMEOUT_S)
            except subprocess.TimeoutExpired:
                print(f":WARNING: Vivado did not exit; killing it. A stale hw_server may remain.", file=sys.stderr)
                self.proc.kill()
                self.proc.wait()

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

    if os.geteuid() != 0:
        execvArgs  = ['/usr/bin/sudo', str(Path(__file__).absolute())] + sys.argv[1:]
        execvArgs += ['--vivado-bin', str(parsed_args.vivado_bin), '--hw-server-bin', str(parsed_args.hw_server_bin)]
        print(f":INFO: Running: {execvArgs}")
        os.execv(execvArgs[0], execvArgs)

    bitstream = parsed_args.bitstream.resolve().absolute()
    device = get_bitstream_device(bitstream)
    bdfs = get_bdfs()
    print(f":INFO: Found FireSim BDFs: {bdfs}; bitstream targets {device}")

    disconnected: Set[str] = set()

    def disconnect_all() -> None:
        """Remove every FireSim BDF from the bus before JTAG programming."""
        for bdf in bdfs:
            disconnected.add(bdf)
            disconnect_bdf(bdf)

    def reconnect_all() -> None:
        """Rescan every FireSim BDF back onto the bus."""
        for bdf in bdfs:
            reconnect_bdf(bdf)
            disconnected.discard(bdf)

    serial2BDF: Dict[str, str] = {}

    # FPGAs must be off the PCI-E bus whenever they are (re)programmed
    disconnect_all()
    session = VivadoSession(parsed_args.vivado_bin, bitstream, device)
    try:
        # 1. get serial numbers of all fpgas on the system that match the bitstream
        session.wait("phase1_done", timeout_s=300)
        serials = json.loads((session.work_dir / "serials.json").read_text())
        print(f":INFO: Found {device} JTAG serials: {[s['uid'] for s in serials]}")
        if len(serials) != len(bdfs):
            sys.exit(f":ERROR: Found {len(serials)} {device} FPGA(s) on JTAG but {len(bdfs)} FireSim FPGA(s) on PCI-E. "
                     f"Every {device} FPGA must be running a FireSim bitstream. See {session.log}")
        session.signal("start_phase2")

        # 2. program all fpgas so that they are in a known state
        status = session.wait("phase2_done", timeout_s=60 + PROGRAM_TIMEOUT_S * len(serials))
        if status != "OK":
            sys.exit(f":ERROR: Programming all FPGAs failed. See {session.log}")
        reconnect_all()

        # 3. write to all fingerprints based on bdfs
        write_val = 0xDEADBEEF
        for bdf in bdfs:
            run_driver_write_fingerprint(bdf, parsed_args.driver, write_val)

        # 4. create mapping by checking if fingerprint was overridden
        for idx, entry in enumerate(serials):
            serial = entry["uid"]

            disconnect_all()
            session.signal(f"go_{idx}")
            status = session.wait(f"done_{idx}", timeout_s=PROGRAM_TIMEOUT_S)
            if status != "OK":
                sys.exit(f":ERROR: Reprogramming {serial} failed. See {session.log}")
            reconnect_all()

            # read all fingerprints to find the good one
            for bdf in bdfs:
                if not (bdf in serial2BDF.values()):
                    rc = run_driver_check_fingerprint(bdf, parsed_args.driver)
                    if rc == 0:
                        serial2BDF[serial] = bdf
                        break

            if not (serial in serial2BDF):
                sys.exit(f":ERROR: Unable to determine BDF for {serial} FPGA. Something went wrong")

        session.wait("phase3_done", timeout_s=60)
    finally:
        session.close()
        for bdf in list(disconnected):
            reconnect_bdf(bdf)

    print(f":INFO: Mapping: {serial2BDF}")

    finalMap = []
    for entry in serials:
        finalMap.append({
            "uid" : entry["uid"],
            "device" : entry["device"],
            "bdf" : serial2BDF[entry["uid"]]
        })

    with open(parsed_args.out_db_json, 'w') as f:
        json.dump(finalMap, f, indent=2)

    print(f":INFO: Successfully wrote to {parsed_args.out_db_json}")

    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
