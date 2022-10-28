#!/usr/bin/env python3

from fabric.api import *
import argparse

from common import manager_ci_dir, set_fabric_firesim_pem
from platform_lib import Platform, get_platform_enum
from ci_variables import ci_azure_sub_id, ci_azure_client_id, ci_azure_tenant_id, ci_azure_client_secret, \
    ci_workflow_run_id, ci_personal_api_token

def generate_azure_credited_env():
    run(f"echo 'export AZURE_CREDITED_ENV=true' >> ~/azure_env.sh")
    run(f"echo 'export AZURE_SUBSCRIPTION_ID={ci_azure_sub_id}' >> ~/azure_env.sh")
    run(f"echo 'export AZURE_CLIENT_ID={ci_azure_client_id}' >> ~/azure_env.sh")
    run(f"echo 'export AZURE_TENANT_ID={ci_azure_tenant_id}' >> ~/azure_env.sh")
    run(f"echo 'export AZURE_CLIENT_SECRET={ci_azure_client_secret}' >> ~/azure_env.sh")

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
        
        if platform == Platform.AZURE:
            generate_azure_credited_env()
            azure_source_string = 'source azure_env.sh;'
        else:
            azure_source_string = ''

        # Put a baseline time-to-live bound on the manager.
        # Instances will be terminated (since they are spot requests) and cleaned up in a nightly job.

        # Setting pty=False is required to stop the screen from being
        # culled when the SSH session associated with the run command ends.

        
        run("echo 'zombie kr' >> ~/.screenrc") # for testing purposes, keep the screen on even after it dies
        run((f"screen -S ttl -dm bash -c \'{azure_source_string}sleep {int(max_runtime) * 3600};"
            f"./change-workflow-instance-states.py {platform} {ci_workflow_run_id} terminate {ci_personal_api_token}\'")
            , pty=False)
        run((f"screen -S workflow-monitor -L -dm bash -c" 
            f"\'{azure_source_string}./workflow-monitor.py {platform} {ci_workflow_run_id} {ci_personal_api_token}\'")
            , pty=False)

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
