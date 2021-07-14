#!/usr/bin/env python

from fabric.api import *

from common import manager_fsim_dir, manager_hostname
from ci_variables import ci_workflow_id

def build_default_workloads():
    """ Builds workloads that will be run on F1 instances as part of CI """

    with cd(manager_fsim_dir), \
         prefix('source env.sh'), \
         cd("target-design/chipyard/software/firemarshal"):
        run("./init-submodules.sh")
        run("./marshal -v build br-base.json")

    with cd(manager_fsim_dir), \
         prefix('source env.sh'), \
         cd("deploy/workloads"):
        run("make linux-poweroff")


if __name__ == "__main__":
    execute(build_default_workloads, hosts=[manager_hostname(ci_workflow_id)])
