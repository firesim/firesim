#!/usr/bin/env python3

import sys

from fabric.api import *

from common import manager_fsim_dir, set_fabric_firesim_pem
from ci_variables import ci_workflow_run_id

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
            # rename runfarm tag with a unique tag based on the ci workflow
            with prefix('export FIRESIM_RUNFARM_PREFIX={}'.format(ci_workflow_run_id)):
                rc = 0
                with settings(warn_only=True):
                    # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    rc = run("timeout {} ./deploy/workloads/run-workload.sh {} --withlaunch &> {}.log".format(timeout, workload, workload), pty=False).return_code
                if rc != 0:
                    # need to confirm that instance is off
                    print("Workload {} failed. Printing last lines of log. See {}.log for full info".format(workload, workload))
                    print("Log start:")
                    run("tail -n 100 {}.log".format(workload))
                    print("Log end.")
                    print("Terminating workload")
                    run("firesim terminaterunfarm -q -c {}".format(workload))
                    sys.exit(rc)
                else:
                    print("Workload {} successful.".format(workload))

        run_w_timeout("{}/deploy/workloads/linux-poweroff-all-no-nic.ini".format(manager_fsim_dir), "30m")
        run_w_timeout("{}/deploy/workloads/linux-poweroff-nic.ini".format(manager_fsim_dir), "30m")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_linux_poweroff, hosts=["localhost"])
