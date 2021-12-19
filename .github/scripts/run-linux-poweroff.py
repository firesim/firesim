#!/usr/bin/env python3

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem

def run_linux_poweroff():
    """ Runs Linux poweroff workloads """

    with prefix('cd {} && source sourceme-f1-manager.sh'.format(manager_fsim_dir)):
        run("cd sw/firesim-software && ./marshal -v build br-base.json && ./marshal -v install br-base.json")
        run("cd deploy/workloads/ && make linux-poweroff")

        def run_w_timeout(workload, timeout):
            """ Run workload with a specific timeout

            :arg: workload (str) - workload ini (abs path)
            :arg: timeout (str) - timeout amount for the workload to run
            """
            rc = 0
            with settings(warn_only=True):
                rc = run("timeout {} ./deploy/workloads/run-workload.sh {} --withlaunch".format(timeout, workload)).return_code
            if rc != 0:
                # need to confirm that instance is off
                run("firesim terminaterunfarm -q -c {}".format(workload))
                sys.exit(1)

        run_w_timeout("{}/deploy/workloads/linux-poweroff-all-no-nic.ini".format(manager_fsim_dir), "30m")
        run_w_timeout("{}/deploy/workloads/linux-poweroff-nic.ini".format(manager_fsim_dir), "30m")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_linux_poweroff, hosts=["localhost"])
