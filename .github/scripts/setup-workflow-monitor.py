#!/usr/bin/env python3

from fabric.api import *
import sys

from common import manager_ci_dir, set_fabric_firesim_pem
from ci_variables import ci_workflow_run_id, ci_personal_api_token

def setup_workflow_monitor(max_runtime: int) -> None:
    """ Performs the prerequisite tasks for all CI jobs that will run on the manager instance

    max_runtime (hours): The maximum uptime this manager and its associated
        instances should have before it is stopped. This serves as a redundant check
        in case the workflow-monitor is brought down for some reason.
    """
    with cd(manager_ci_dir):
        # Put a baseline time-to-live bound on the manager.
        # Instances will be terminated (since they are spot requests) and cleaned up in a nightly job.

        # Setting pty=False is required to stop the screen from being
        # culled when the SSH session associated with the run command ends.
        run("screen -S ttl -dm bash -c \'sleep {}; ./change-workflow-instance-states.py {} terminate {}\'"
            .format(int(max_runtime) * 3600, ci_workflow_run_id, ci_personal_api_token), pty=False)
        run("screen -S workflow-monitor -L -dm ./workflow-monitor.py {} {}"
            .format(ci_workflow_run_id, ci_personal_api_token), pty=False)


if __name__ == "__main__":
    set_fabric_firesim_pem()
    max_runtime = sys.argv[1]
    execute(setup_workflow_monitor, max_runtime, hosts=["localhost"])
