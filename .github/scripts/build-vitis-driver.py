#!/usr/bin/env python3

from fabric.api import prefix, run, execute # type: ignore

from ci_variables import ci_env

def build_vitis_driver():
    """Runs compilation of Vitis driver for the make-default tuple"""

    # assumptions:
    #   - the firesim repo is already setup in a prior script
    #   - machine-launch-script requirements are already installed

    with prefix(f"cd {ci_env['GITHUB_WORKSPACE']}"):
        with prefix('source sourceme-f1-manager.sh --skip-ssh-setup'):
            with prefix("cd ./sim"):
                run("make PLATFORM=vitis vitis")

if __name__ == "__main__":
    execute(build_vitis_driver, hosts=["localhost"])
