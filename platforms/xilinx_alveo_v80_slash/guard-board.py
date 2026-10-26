#!/usr/bin/env python3
# See LICENSE for license details.
"""Reject programming while a native FireSim driver is active on the host."""
import os
from pathlib import Path


def active_drivers(proc=Path('/proc')):
    found = []
    for process in proc.iterdir():
        if not process.name.isdigit() or int(process.name) == os.getpid():
            continue
        try:
            executable = Path(os.readlink(process / 'exe'))
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError as error:
            raise RuntimeError('Run the process guard with host privileges') from error
        name = executable.name.removesuffix(' (deleted)')
        if (name.startswith('FireSim') or name == 'qualified-driver' or
                name.endswith(('-xilinx_v80', '-xilinx_alveo_v80_slash'))):
            found.append((process.name, str(executable)))
    return found


if __name__ == '__main__':
    found = active_drivers()
    print('Active board drivers:', found)
    raise SystemExit(2 if found else 0)
