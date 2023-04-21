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

    with prefix(f"cd {ci_env['GITHUB_WORKSPACE']}"):
        run("./build-setup.sh --skip-validate")
        with prefix('source sourceme-f1-manager.sh --skip-ssh-setup'):
            run("firesim managerinit --platform vitis")
            with prefix("cd deploy"):
                run("cat config_runtime.yaml")
                run("cat ../docs/Running-OnPrem-Simulations-Tutorial/DOCS_EXAMPLE_config_runtime.yaml")
                run("diff config_runtime.yaml ../docs/Running-OnPrem-Simulations-Tutorial/DOCS_EXAMPLE_config_runtime.yaml")

if __name__ == "__main__":
    execute(run_docs_generated_components_check, hosts=["localhost"])
