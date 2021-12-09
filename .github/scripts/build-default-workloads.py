#!/usr/bin/env python

from fabric.api import *

from common import manager_fsim_dir, manager_hostname
from ci_variables import ci_workflow_id

def build_default_workloads():
    """ Builds workloads that will be run on F1 instances as part of CI """

    with prefix('cd {} && source ./env.sh'.format(manager_fsim_dir)), \
         prefix('cd deploy/workloads'):
        run("marshal -v build br-base.json")
        run("make linux-poweroff")
        run("make allpaper")

if __name__ == "__main__":
    execute(build_default_workloads, hosts=[manager_hostname(ci_workflow_id)])
