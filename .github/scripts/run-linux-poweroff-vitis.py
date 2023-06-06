#!/usr/bin/env python3

import sys
from fabric.api import prefix, run, settings, execute # type: ignore

from ci_variables import ci_env
from utils import search_match_in_last_workloads_output_file

def run_linux_poweroff_vitis():
    """ Runs Base Vitis Build """

    # assumptions:
    #   - machine-launch-script requirements are already installed
    #   - XILINX_VITIS, XILINX_XRT, XILINX_VIVADO are setup (in env / LD_LIBRARY_PATH / path / etc)

    # repo should already be checked out

    with prefix(f"cd {ci_env['REMOTE_WORK_DIR']}"):
        run("./build-setup.sh --skip-validate")
        with prefix('source sourceme-manager.sh --skip-ssh-setup'):
            with prefix('cd sw/firesim-software'):
                # build outputs.yaml (use this workload since firemarshal can guestmount)
                run("./marshal -v build test/outputs.yaml")
                run("./marshal -v install test/outputs.yaml")

            def run_w_timeout(workload_path, workload, timeout, num_passes):
                log_tail_length = 300
                rc = 0
                with settings(warn_only=True):
                    # avoid logging excessive amounts to prevent GH-A masking secrets (which slows down log output)
                    # pty=False needed to avoid issues with screen -ls stalling in fabric
                    rc = run(f"timeout {timeout} {workload_path}/run-workload.sh {workload_path}/config_runtime.yaml &> {workload}.log", pty=False).return_code
                    print(f" Printing last {log_tail_length} lines of log. See {workload}.log for full info.")
                    run(f"tail -n {log_tail_length} {workload}.log")

                    # This is a janky solution to the fact the manager does not
                    # return a non-zero exit code or some sort of result summary.
                    # The expectation here is that the PR author will manually
                    # check these output files for correctness until it can be
                    # done programmatically..
                    print(f"Printing last {log_tail_length} lines of all output files. See results-workload for more info.")
                    run(f"""cd deploy/results-workload/ && LAST_DIR=$(ls | tail -n1) && if [ -d "$LAST_DIR" ]; then tail -n{log_tail_length} $LAST_DIR/*/*; fi""")

                    run(f"firesim terminaterunfarm -q -c {workload_path}/config_runtime.yaml")

                if rc != 0:
                    print(f"Workload {workload} failed.")
                    sys.exit(rc)
                else:
                    print(f"Workload run {workload} successful. Checking workload files...")

                    def check(match_key, file_name = 'uartlog'):
                        out_count = search_match_in_last_workloads_output_file(file_name, match_key)
                        assert out_count == num_passes, f"Workload {file_name} files are malformed: '{match_key}' found {out_count} times (!= {num_passes}). Something went wrong."

                    # first driver completed successfully
                    check('*** PASSED ***')

                    # verify login was reached (i.e. linux booted)
                    check('running /etc/init.d/S99run')

                    # verify reaching poweroff
                    check('Power down')

                    print(f"Workload run {workload} successful.")

            run_w_timeout(f"{ci_env['GITHUB_WORKSPACE']}/deploy/workloads/ci/vitis", "linux-poweroff-singlenode", "30m", 1)

if __name__ == "__main__":
    execute(run_linux_poweroff_vitis, hosts=["localhost"])
