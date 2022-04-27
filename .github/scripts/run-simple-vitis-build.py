#!/usr/bin/env python3

import sys

from fabric.api import *
from ci_variables import ci_workdir

# Common fabric settings (for now copied from common)
env.output_prefix = False
env.abort_on_prompts = True
env.timeout = 100
env.connection_attempts = 10
env.disable_known_hosts = True
env.keepalive = 60 # keep long SSH connections running

def run_simple_vitis_build():
    """ Runs Base Vitis Build """

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)
    #   - RISCV toolchain is already installed

    # repo should already be checked out

    # TODO: Make this work locally
    #run("./build-setup.sh --fast")

    # HACK: take the RISC-V toolchain prebuilt
    with prefix('source /scratch/abejgonza/chipyard-work/chipyard/env-riscv-tools.sh && cd {}'.format(ci_workdir)):
        # HACK: hacked around build-setup
        run("./local-build-setup.sh --skip-toolchain --skip-validate")

        # HACK: setup FireSim's env.sh
        with prefix('source sourceme-f1-manager.sh'):
            with prefix('cd sim/'):
                rc = 0
                with settings(warn_only=True):
                    # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    rc = run("timeout 1h make PLATFORM=vitis DESIGN=FireSim TARGET_CONFIG=FireSimRocketConfig PLATFORM_CONFIG=BaseVitisConfig replace-rtl &> vitis.log", pty=False).return_code
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
    execute(run_simple_vitis_build, hosts=["localhost"])
