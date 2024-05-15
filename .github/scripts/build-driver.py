#!/usr/bin/env python3

import argparse
from enum import Enum
from fabric.api import prefix, run, execute # type: ignore

from ci_variables import ci_env

class FpgaPlatform(Enum):
    vitis = 'vitis'
    f1 = 'f1'
    xilinx_alveo_u250 = 'xilinx_alveo_u250'

    def __str__(self):
        return self.value

parser = argparse.ArgumentParser(description='')
parser.add_argument('--platform', type=FpgaPlatform, choices=list(FpgaPlatform), required=True)
args = parser.parse_args()

def build_driver():
    """Runs compilation of FireSim driver for the make-default tuple"""

    # assumptions:
    #   - the firesim repo is already setup in a prior script
    #   - machine-launch-script requirements are already installed

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            with prefix("cd ./sim"):
                run(f"make PLATFORM={args.platform} {args.platform}")

if __name__ == "__main__":
    execute(build_driver, hosts=["localhost"])
