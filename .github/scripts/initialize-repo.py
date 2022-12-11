#!/usr/bin/env python3

from fabric.api import cd, run, execute # type: ignore

from common import manager_home_dir, manager_fsim_dir, manager_marshal_dir, set_fabric_firesim_pem
# This is expected to be launch from the ci container
from ci_variables import ci_env

def initialize_repo():
    """ Initializes firesim repo: clones, runs build-setup, and intializes marshal submodules """

    with cd(manager_home_dir):
        run("rm -rf {}".format(manager_fsim_dir))
        # copy ci version of the repo into the new globally accessible location
        run("git clone {} {}".format(ci_env['GITHUB_WORKSPACE'], manager_fsim_dir))

    with cd(manager_fsim_dir):
        run("./build-setup.sh --skip-validate")

    # Initialize marshal submodules early because it appears some form of
    # contention between submodule initialization and the jgit SBT plugin
    # causes SBT to lock up, causing downstream scala tests to fail when
    # run concurrently with ./init-submodules.sh
    with cd(manager_marshal_dir):
        run("./init-submodules.sh")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(initialize_repo, hosts=["localhost"])
