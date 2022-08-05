#!/usr/bin/env python3

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem

def build_default_workloads():
    """ Builds workloads that will be run on F1 instances as part of CI """

    with prefix('cd {} && source ./env.sh'.format(manager_fsim_dir)), \
         prefix('cd deploy/workloads'):

        run("cat /proc/self/limits && marshal -v build br-base.json")

        run("make linux-poweroff")
        run("make allpaper")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(build_default_workloads, hosts=["localhost"])
