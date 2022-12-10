#!/usr/bin/env python3

from fabric.api import cd, shell_env, run, execute # type: ignore
import argparse
import time
import os

from common import manager_ci_dir, set_fabric_firesim_pem
from platform_lib import Platform, get_platform_enum
from github_common import get_issue_number
from ci_variables import RUN_LOCAL, ci_env

def setup_workflow_monitor(platform: Platform, max_runtime: int) -> None:
    """ Performs the prerequisite tasks for all CI jobs that will run on the manager instance

    max_runtime (hours): The maximum uptime this manager and its associated
        instances should have before it is stopped. This serves as a redundant check
        in case the workflow-monitor is brought down for some reason.

    platform: Enum that indicates either 'aws' or 'azure' currently. Describes the current platform
        from which CI is being run from.
    """
    with cd(manager_ci_dir):
        # This generates a file that can be sourced to get all the right keys / ids to run any of the
        # Azure jobs. On testing, the environment variables did not correctly pass themselves to the
        # screen job on
        assert not RUN_LOCAL, "Workflow monitor setup only works running under GH-A"

        workflow_log = f"{manager_ci_dir}/workflow-monitor-screen.log"

        #run("echo 'zombie kr' >> ~/.screenrc") # for testing purposes, keep the screen on even after it dies
        with shell_env(**ci_env):
            # Put a baseline time-to-live bound on the manager.
            # Instances will be terminated (since they are spot requests) or will cleaned up in a nightly job.
            # Setting pty=False is required to stop the screen from being
            # culled when the SSH session associated with the run command ends.
            run((f"screen -S ttl -dm bash -c \'sleep {int(max_runtime) * 3600};"
                f"./change-workflow-instance-states.py {platform} {ci_env['GITHUB_RUN_ID']} terminate {ci_env['PERSONAL_ACCESS_TOKEN']}\'")
                , pty=False)
            run((f"screen -S workflow-monitor -L -Logfile {workflow_log} -dm bash -c \'./workflow-monitor.py {platform} {get_issue_number()}\'")
                , pty=False)

        time.sleep(30)

        # verify the screen sessions are running
        ls_out = run("screen -ls")
        if not ("ttl" in ls_out and "workflow-monitor" in ls_out):
            print("Error: Unable to find 'ttl' or 'workflow-monitor' screen sessions in 'screen -ls' after spawn.")
            print("'screen -ls' output:")
            print(ls_out)
            exit(1)

        # verify the workflow monitor started correctly
        log_out = run(f"cat {workflow_log}")
        if not "Workflow monitor started" in log_out:
            print("Error: Workflow monitor not started properly.")
            print("Workflow monitor log output:")
            print(log_out)
            exit(1)

if __name__ == "__main__":
    set_fabric_firesim_pem()

    parser = argparse.ArgumentParser()
    # Remove the all option, since we only perform setup a single platform at a time
    platform_choices = [str(p) for p in Platform]
    platform_choices.remove('all')
    parser.add_argument('platform',
                        choices = platform_choices,
                        help = "The platform CI is being run on")
    parser.add_argument('max_runtime',
                        help="""The maximum uptime this manager and its associated
                        instances should have before it is stopped. This serves as a redundant check
                        in case the workflow-monitor is brought down for some reason.""")
    args = parser.parse_args()
    platform = get_platform_enum(args.platform)

    execute(setup_workflow_monitor, platform, args.max_runtime, hosts=["localhost"])
