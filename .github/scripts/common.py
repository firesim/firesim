import sys
import boto3
import os
from fabric.api import *
import requests

# Reuse manager utilities
script_dir = os.path.dirname(os.path.realpath(__file__)) + "/../.."
sys.path.append(script_dir + "/deploy/awstools")
from awstools import get_instances_with_filter

# Remote paths
manager_home_dir = "/home/centos"
manager_fsim_pem = manager_home_dir + "/firesim.pem"
manager_fsim_dir = manager_home_dir + "/firesim"
manager_marshal_dir = manager_fsim_dir + "/target-design/chipyard/software/firemarshal"
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

def deregister_runner_if_exists(gh_token, runner_name):
    headers = {'Authorization': "token {}".format(gh_token.strip())}

    # Check if exists before deregistering
    r = requests.get("https://api.github.com/repos/firesim/firesim/actions/runners", headers=headers)
    if r.status_code != 200:
        # if couldn't delete then just exit
        return

    res_dict = r.json()
    runner_list = res_dict["runners"]
    for runner in runner_list:
        if runner_name in runner["name"]:
            r = requests.delete("https://api.github.com/repos/firesim/firesim/actions/runners/{}".format(runner["id"]), headers=headers)
            if r.status_code != 204:
                # if couldn't delete then just exit
                return

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
        deregister_runner_if_exists(gh_token, tag_value)
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
        deregister_runner_if_exists(gh_token, tag_value)
        client.terminate_instances(InstanceIds=instance_ids, DryRun=dryrun)
    else:
        raise ValueError("Unrecognized transition type: {}".format(state_change))

def terminate_workflow_instances(gh_token, tag_value, dryrun=False):
    change_workflow_instance_states(gh_token, tag_value, "terminate", dryrun)

def stop_workflow_instances(gh_token, tag_value, dryrun=False):
    change_workflow_instance_states(gh_token, tag_value, "stop", dryrun)

def start_workflow_instances(gh_token, tag_value, dryrun=False):
    change_workflow_instance_states(gh_token, tag_value, "start", dryrun)
