from pathlib import Path
import subprocess

from typing import List

def call_vivado(vivado: Path, args: List[str]) -> tuple[int, str, str]:
    pVivado = subprocess.Popen(
        [
            str(vivado),
            '-mode', 'tcl',
            '-nolog', '-nojournal', '-notrace',
        ] + args,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE
    )

    sout, serr = pVivado.communicate()

    return (pVivado.returncode, sout.decode('utf-8'), serr.decode('utf-8'))
