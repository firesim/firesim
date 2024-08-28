#!/usr/bin/env python3

import sys
from fabric.api import prefix, run, execute # type: ignore

import fabric_cfg
from ci_variables import ci_env
from utils import setup_shell_env_vars

def invoke_script(script):
    """Runs a FireSim script from the top-level"""

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            with prefix(setup_shell_env_vars()):
                run(script)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise Exception("Invalid number of args")
    execute(invoke_script, sys.argv[1], hosts=["localhost"])
