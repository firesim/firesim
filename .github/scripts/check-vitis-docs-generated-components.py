#!/usr/bin/env python3

import sys
from fabric.api import prefix, run, settings, execute # type: ignore

from ci_variables import ci_env

def run_docs_generated_components_check():
    """ Runs checks to make sure generated components of vitis docs have been
    updated. """

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            with prefix("cd deploy"):
                run("cat config_runtime.yaml")
                path = "docs/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Running-Simulations/DOCS_EXAMPLE_config_runtime.yaml"
                run(f"cat ../{path}")
                run(f"diff config_runtime.yaml ../{path}")

if __name__ == "__main__":
    execute(run_docs_generated_components_check, hosts=["localhost"])
