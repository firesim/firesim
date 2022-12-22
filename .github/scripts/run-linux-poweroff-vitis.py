#!/usr/bin/env python3

import sys
from fabric.api import prefix, run, settings, execute # type: ignore

from ci_variables import ci_env

def run_linux_poweroff_vitis():
    """ Runs Base Vitis Build """

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    with prefix(f"cd {ci_env['GITHUB_WORKSPACE']}"):
        run("./build-setup.sh --skip-validate")
        with prefix('source sourceme-f1-manager.sh --skip-ssh-setup'):
            # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
            with prefix('cd sw/firesim-software'):
                run("./init-submodules.sh")

                # build outputs.yaml (use this workload since firemarshal can guestmount)
                with settings(warn_only=True):
                    rc = run("./marshal -v build test/outputs.yaml &> outputs.full.log").return_code
                    if rc != 0:
                        run("cat outputs.full.log")
                        raise Exception("Building test/outputs.yaml failed to run")

                run("./marshal -v install test/outputs.yaml")

            # download prebuilt xclbin to /tmp
            with prefix('cd /tmp'):
                run('wget https://people.eecs.berkeley.edu/~abe.gonzalez/firesim_rocket_singlecore_no_nic_2c251a.xclbin')

            def run_w_timeout(workload_path, workload, timeout):
                log_tail_length = 100
                rc = 0
                with settings(warn_only=True):
                    # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    rc = run(f"timeout {timeout} {workload_path}/run-workload.sh {workload_path}/config_runtime.yaml {workload_path}/config_hwdb.yaml {workload_path}/config_build_recipes.yaml &> {workload}.log", pty=False).return_code
                    print(f" Printing last {log_tail_length} lines of log. See {workload}.log for full info.")
                    run(f"tail -n {log_tail_length} {workload}.log")

                    # This is a janky solution to the fact the manager does not
                    # return a non-zero exit code or some sort of result summary.
                    # The expectation here is that the PR author will manually
                    # check these output files for correctness until it can be
                    # done programmatically..
                    print(f"Printing last {log_tail_length} lines of all output files. See results-workload for more info.")
                    run(f"""cd deploy/results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then tail -n{log_tail_length} $LAST_DIR/*/*; fi""")

                    run(f"firesim terminaterunfarm -q -c {workload_path}/config_runtime.yaml -a {workload_path}/config_hwdb.yaml -r {workload_path}/config_build_recipes.yaml")

                if rc != 0:
                    print(f"Workload {workload} failed.")
                    sys.exit(rc)
                else:
                    print(f"Workload {workload} successful.")

            run_w_timeout(f"{ci_env['GITHUB_WORKSPACE']}/.github/scripts/vitis-test", "linux-poweroff-singlenode", "30m")

if __name__ == "__main__":
    execute(run_linux_poweroff_vitis, hosts=["localhost"])
