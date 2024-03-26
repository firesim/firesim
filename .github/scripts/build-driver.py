#!/usr/bin/env python3

import argparse
from fabric.api import prefix, run, execute # type: ignore

from ci_variables import ci_env

parser = argparse.ArgumentParser(description='')
parser.add_argument('--platform', type=str, required=True, help='vitis or xilinx_alveo_u250')
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
    execute(build_vitis_driver, hosts=["localhost"])
