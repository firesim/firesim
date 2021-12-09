#!/usr/bin/env python3

from fabric.api import *

from common import manager_fsim_dir, manager_hostname, set_fabric_firesim_pem
from ci_variables import ci_workflow_id

def run_manager_pytests():
    """ Runs all manager pytests """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("cd deploy && python3 -m pytest")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_manager_pytests, hosts=["localhost"])
