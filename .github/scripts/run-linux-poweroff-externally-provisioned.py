#!/usr/bin/env python3

import sys
import time
from pathlib import Path

from fabric.api import prefix, settings, run, execute # type: ignore

from common import manager_fsim_dir, set_fabric_firesim_pem
from ci_variables import ci_env
sys.path.append(ci_env['GITHUB_WORKSPACE'] + "/deploy")
from awstools.awstools import get_instances_with_filter, get_private_ips_for_instances
from util.filelineswap import file_line_swap

def run_linux_poweroff_externally_provisioned():
    """ Runs Linux poweroff workloads using externally provisioned AWS run farm """

    with prefix(f"cd {manager_fsim_dir} && source sourceme-f1-manager.sh"):

        def run_w_timeout(workload_path, workload, timeout):
            """ Run workload with a specific timeout

            :arg: workload_path (str) - workload abs path
            :arg: workload (str) - workload yaml name
            :arg: timeout (str) - timeout amount for the workload to run
            """
            workload_full = workload_path + "/" + workload
            log_tail_length = 300
            script_name = Path(__file__).stem
            rf_prefix = f"{ci_env['GITHUB_RUN_ID']}-{script_name}"

            # unique tag based on the ci workflow and filename is needed to ensure
            # run farm is unique to each linux-poweroff test
            with prefix(f"export FIRESIM_RUNFARM_PREFIX={rf_prefix}"):
                rc = 0
                with settings(warn_only=True):
                    # do the following:
                    #   1. launch the run farm w/ the AWS EC2 runfarm
                    #   2. copy the hostnames given into a new externally provisioned runfarm/runtime file
                    #   4. run launch/infra/runworkload/terminate w/ that runtime
                    #   5. if successful or fail, run the terminate w/ the old AWS EC2 runfarm

                    rc = run(f"firesim launchrunfarm -c {workload_full}")

                    time.sleep(3 * 60) # TODO: replace w/ instance_liveness check

                    instances_filter = [
                            {'Name': 'instance-type', 'Values': ['f1.2xlarge']},
                            {'Name': 'tag:fsimcluster', 'Values': [f'{rf_prefix}*']},
                            ]
                    instances = get_instances_with_filter(instances_filter, allowed_states=["running"])
                    instance_ips = [instance['PrivateIpAddress'] for instance in instances]

                    start_lines = [f"  base_recipe: run-farm-recipes/externally_provisioned.yaml\n"]
                    start_lines += ["  recipe_arg_overrides:\n"]
                    start_lines += ["    run_farm_hosts_to_use:\n"]
                    for ip in instance_ips:
                        start_lines += [f"""      - "centos@{ip}": one_fpga_spec\n"""]

                    file_line_swap(
                            workload_full,
                            f"/tmp/{workload}",
                            "ci replace start",
                            "ci replace end",
                            start_lines)

                    # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    rc = run(f"timeout {timeout} ./deploy/workloads/run-workload.sh /tmp/{workload} --withlaunch &> {workload}.log", pty=False).return_code
                    print(f" Printing last {log_tail_length} lines of log. See {workload}.log for full info.")
                    run(f"tail -n {log_tail_length} {workload}.log")

                    # This is a janky solution to the fact the manager does not
                    # return a non-zero exit code or some sort of result summary.
                    # The expectation here is that the PR author will manually
                    # check these output files for correctness until it can be
                    # done programmatically..
                    print(f"Printing last {log_tail_length} lines of all output files. See results-workload for more info.")
                    run(f"""cd deploy/results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then tail -n{log_tail_length} $LAST_DIR/*/*; fi""")

                    run(f"firesim terminaterunfarm -q -c {workload_full}")

                if rc != 0:
                    print(f"Workload {workload} failed.")
                    sys.exit(rc)
                else:
                    print(f"Workload {workload} successful.")

        run_w_timeout(f"{manager_fsim_dir}/deploy/workloads", "linux-poweroff-all-no-nic.yaml", "45m")

if __name__ == "__main__":
    set_fabric_firesim_pem()
    execute(run_linux_poweroff_externally_provisioned, hosts=["localhost"])
