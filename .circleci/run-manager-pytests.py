#!/usr/bin/env python

from fabric.api import *

from common import manager_fsim_dir, manager_hostname
from ci_variables import ci_workflow_id

def run_manager_pytests():
    """ Runs all manager pytests """

    with cd(manager_fsim_dir), prefix('source env.sh'):
        run("cd deploy && python -m pytest")

if __name__ == "__main__":
    execute(run_manager_pytests, hosts=[manager_hostname(ci_workflow_id)])
