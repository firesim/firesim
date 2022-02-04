#!/usr/bin/env python3

import sys

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_simple_vitis_build():
    """ Runs Base Vitis Build """

    with prefix('cd {} && source sourceme-f1-manager.sh'.format(manager_fsim_dir)):
        with prefix('cd sim/'):
            rc = 0
            with settings(warn_only=True):
                # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                # pty=False needed to avoid issues with screen -ls stalling in fabric
                rc = run("timeout 1h make DESIGN=FireSim TARGET_CONFIG=FireSimRocketConfig PLATFORM_CONFIG=BaseVitisConfig replace-rtl &> vitis.log", pty=False).return_code
            if rc != 0:
                # need to confirm that instance is off
                print("Vitis replace-rtl failed. Printing last lines of log. See vitis.log for full info")
                print("Log start:")
                run("tail -n 100 vitis.log")
                print("Log end.")
                sys.exit(rc)
            else:
                print("Vitis replace-rtl successful.")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_simple_vitis_build, hosts=["localhost"])
