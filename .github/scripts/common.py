import sys
import boto3
import os
import math
from fabric.api import *
import requests
from ci_variables import ci_firesim_dir, local_fsim_dir, ci_gha_api_url, ci_repo_name

from typing import Dict, List, Any

# Reuse manager utilities
# Note: ci_firesim_dir must not be used here because the persistent clone my not be initialized yet.
sys.path.append(local_fsim_dir + "/deploy")
from awstools.awstools import get_instances_with_filter

# Github URL related constants
gha_api_url         = f"{ci_gha_api_url}/repos/{ci_repo_name}/actions"
gha_runners_api_url = f"{gha_api_url}/runners"
gha_runs_api_url    = f"{gha_api_url}/runs"

# Remote paths
manager_home_dir = "/home/centos"
manager_fsim_pem = manager_home_dir + "/firesim.pem"
manager_fsim_dir = ci_firesim_dir
manager_marshal_dir = manager_fsim_dir + "/sw/firesim-software"
manager_ci_dir = manager_fsim_dir + "/.github/scripts"

# Common fabric settings
env.output_prefix = False
env.abort_on_prompts = True
env.timeout = 100
env.connection_attempts = 10
env.disable_known_hosts = True
env.keepalive = 60 # keep long SSH connections running

def set_fabric_firesim_pem():
    env.key_filename = manager_fsim_pem

# This tag is common to all instances launched as part of a given workflow
unique_tag_key = 'ci-workflow-id'

# Produce a filter that return all instances associated with this workflow
def get_ci_filter(tag_value):
    return {'Name': 'tag:' + unique_tag_key, 'Values' : [tag_value]}

# Managers additionally have this empty tag defined
manager_filter = {'Name': 'tag:ci-manager', 'Values' : ['']}

def get_manager_tag_dict(sha, tag_value):
    """ Populates a set of tags for the manager of our CI run """
    return {
        'ci-commit-sha1': sha,
        'ci-manager':'',
        unique_tag_key: tag_value}

def get_manager_instance(tag_value):
    """ Looks up the manager instance dict using the CI workflow run's unique tag"""
    instances = get_instances_with_filter([get_ci_filter(tag_value), manager_filter])
    if instances:
        assert len(instances) == 1
        return instances[0]
    else:
        return None

def get_manager_ip(tag_value):
    """ Looks up the manager IP using the CI workflow run's unique tag"""

    manager = get_manager_instance(tag_value)
    if manager is None:
        print("No manager instance running with tag matching the assigned workflow id\n")
        sys.exit(1)
    else:
        return manager['PublicIpAddress']

def manager_hostname(tag_value):
    return "centos@{}".format(get_manager_ip(tag_value))

def get_all_workflow_instances(tag_value):
    """ Grabs a list of all instance dicts sharing the CI workflow run's unique tag """
    return get_instances_with_filter([get_ci_filter(tag_value)])

def instance_metadata_str(instance):
    """ Pretty prints instance info, including ID, state, and public IP """
    static_md = """    Instance ID: {}
    Instance State: {}""".format(instance['InstanceId'], instance['State']['Name'])

    dynamic_md = ""

    if instance.get('PublicIpAddress') is not None:
        dynamic_md = """
    Instance IP: {}""".format(instance['PublicIpAddress'])

    return static_md + dynamic_md

def get_header(gh_token: str) -> Dict[str, str]:
    return {"Authorization": f"token {gh_token.strip()}", "Accept": "application/vnd.github+json"}

def get_runners(gh_token: str) -> List:
    r = requests.get(gha_runners_api_url, headers=get_header(gh_token))
    if r.status_code != 200:
        raise Exception("Unable to retrieve count of GitHub Actions Runners")
    res_dict = r.json()
    runner_count = res_dict["total_count"]

    runners = []
    for page_idx in range(math.ceil(runner_count / 30)):
        r = requests.get(gha_runners_api_url, params={"per_page" : 30, "page" : page_idx + 1}, headers=get_header(gh_token))
        if r.status_code != 200:
            raise Exception("Unable to retrieve (sub)list of GitHub Actions Runners")
        res_dict = r.json()
        runners = runners + res_dict["runners"]

    return runners

def delete_runner(gh_token: str, runner: Dict[str, Any]) -> bool:
    r = requests.delete(f"""{gha_runners_api_url}/{runner["id"]}""", headers=get_header(gh_token))
    if r.status_code != 204:
        print(f"""Unable to delete runner {runner["name"]} with id: {runner["id"]}""")
        return False
    return True

def deregister_offline_runners(gh_token: str) -> None:
    runners = get_runners(gh_token)
    for runner in runners:
        if runner["status"] == "offline":
            delete_runner(gh_token, runner)

def deregister_runners(gh_token: str, runner_name: str) -> None:
    runners = get_runners(gh_token)
    for runner in runners:
        if runner_name in runner["name"]:
            delete_runner(gh_token, runner)

def change_workflow_instance_states(gh_token, tag_value, state_change, dryrun=False):
    """ Change the state of all instances sharing the same CI workflow run's tag. """

    all_instances = get_all_workflow_instances(tag_value)
    manager_instance = get_manager_instance(tag_value)

    # Ensure we do the manager last, as this function may be invoked there
    sorted_instances = [inst for inst in all_instances if inst != manager_instance]
    if manager_instance is not None:
        sorted_instances.append(manager_instance)

    instance_ids = [inst['InstanceId'] for inst in sorted_instances]

    client = boto3.client('ec2')
    if state_change == 'stop':
        print("Stopping instances: {}".format(", ".join(instance_ids)))
        deregister_runners(gh_token, tag_value)
        client.stop_instances(InstanceIds=instance_ids, DryRun=dryrun)
    elif state_change == 'start':
        print("Starting instances: {}".format(", ".join(instance_ids)))
        client.start_instances(InstanceIds=instance_ids, DryRun=dryrun)

        # If we have a manager (typical), wait for it to come up and report its IP address
        if manager_instance is not None:
            print("Waiting on manager instance.")
            manager_id = manager_instance['InstanceId']
            wait_on_instance(manager_id)
            print("Manager ready.")
            # Re-query the instance to get an updated IP address
            print(instance_metadata_str(get_manager_instance(tag_value)))

    elif state_change == 'terminate':
        print("Terminating instances: {}".format(", ".join(instance_ids)))
        deregister_runners(gh_token, tag_value)
        client.terminate_instances(InstanceIds=instance_ids, DryRun=dryrun)
    else:
        raise ValueError("Unrecognized transition type: {}".format(state_change))

def terminate_workflow_instances(gh_token, tag_value, dryrun=False):
    change_workflow_instance_states(gh_token, tag_value, "terminate", dryrun)

def stop_workflow_instances(gh_token, tag_value, dryrun=False):
    change_workflow_instance_states(gh_token, tag_value, "stop", dryrun)

def start_workflow_instances(gh_token, tag_value, dryrun=False):
    change_workflow_instance_states(gh_token, tag_value, "start", dryrun)
