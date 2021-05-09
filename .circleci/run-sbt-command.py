#!/usr/bin/env python

import sys

from fabric.api import *

from common import manager_fsim_dir
from ci_variables import manager_hostname

def run_sbt_command(target_project, command):
    """ Runs a command in SBT shell for the default project specified by the target_project makefrag

    target_project -- The make variable to select the desired target project makefrag

    command -- the command to run
    """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("make -C sim sbt SBT_COMMAND={} TARGET_PROJECT={}".format(command, target_project))

if __name__ == "__main__":
    execute(run_sbt_command, sys.argv[1], sys.argv[2], hosts=[manager_hostname()])
