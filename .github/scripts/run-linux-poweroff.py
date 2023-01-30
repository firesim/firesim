#!/usr/bin/env python3

import sys
from pathlib import Path

from fabric.api import prefix, settings, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem
from ci_variables import ci_env

def run_linux_poweroff():
    """ Runs Linux poweroff workloads """

    with prefix(f"cd {manager_fsim_dir} && source sourceme-f1-manager.sh"):
        def run_w_timeout(workload, timeout):
            """ Run workload with a specific timeout

            :arg: workload (str) - workload yaml (abs path)
            :arg: timeout (str) - timeout amount for the workload to run
            """
            log_tail_length = 300
            # unique tag based on the ci workflow and filename is needed to ensure
            # run farm is unique to each linux-poweroff test
            script_name = Path(__file__).stem
            with prefix(f"export FIRESIM_RUNFARM_PREFIX={ci_env['GITHUB_RUN_ID']}-{script_name}"):
                rc = 0
                with settings(warn_only=True):
                    # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    rc = run(f"timeout {timeout} ./deploy/workloads/run-workload.sh {workload} --withlaunch &> {workload}.log", pty=False).return_code
                    print(f"Printing last {log_tail_length} lines of log. See {workload}.log for full info.")
                    run(f"tail -n {log_tail_length} {workload}.log")

                    # This is a janky solution to the fact the manager does not
                    # return a non-zero exit code or some sort of result summary.
                    # The expectation here is that the PR author will manually
                    # check these output files for correctness until it can be
                    # done programmatically..
                    print(f"Printing last {log_tail_length} lines of all output files. See results-workload for more info.")
                    run(f"""cd deploy/results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then tail -n{log_tail_length} $LAST_DIR/*/*; fi""")

                if rc != 0:
                    # need to confirm that instance is off
                    print(f"Workload {workload} failed. Terminating runfarm.")
                    run(f"firesim terminaterunfarm -q -c {workload}")
                    sys.exit(rc)
                else:
                    print(f"Workload {workload} successful.")

        run_w_timeout(f"{manager_fsim_dir}/deploy/workloads/linux-poweroff-all-no-nic.yaml", "45m")
        run_w_timeout(f"{manager_fsim_dir}/deploy/workloads/linux-poweroff-nic.yaml", "45m")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_linux_poweroff, hosts=["localhost"])
