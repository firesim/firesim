#!/usr/bin/env python3

import sys

from fabric.api import *

from common import manager_fsim_dir, manager_hostname, set_fabric_firesim_pem
from ci_variables import ci_workflow_run_id

def run_sbt_command(target_project, command):
    """ Runs a command in SBT shell for the default project specified by the target_project makefrag

    target_project -- The make variable to select the desired target project makefrag

    command -- the command to run
    """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("make -C sim sbt SBT_COMMAND={} TARGET_PROJECT={}".format(command, target_project))

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_sbt_command, sys.argv[1], sys.argv[2], hosts=["localhost"])
