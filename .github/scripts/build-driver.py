#!/usr/bin/env python3

from fabric.api import prefix, run, execute # type: ignore

import fabric_cfg
from ci_variables import ci_env
from utils import create_args

args = create_args()

def build_driver():
    """Runs compilation of FireSim driver for the make-default tuple"""

    # assumptions:
    #   - the firesim repo is already setup in a prior script
    #   - machine-launch-script requirements are already installed

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            with prefix("cd sim"):
                run(f"make PLATFORM={args.platform} {args.platform}")

if __name__ == "__main__":
    execute(build_driver, hosts=["localhost"])
