#!/usr/bin/env python3

from fabric.api import cd, prefix, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_manager_pytests():
    """ Runs all manager pytests """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("cd deploy && python3 -m pytest")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_manager_pytests, hosts=["localhost"])
