import sys
import boto3
from fabric.api import *

# Remote paths
manager_home_dir = "/home/centos"
manager_fsim_dir = "/home/centos/firesim"
manager_ci_dir = manager_fsim_dir + "/.circleci"

# Common fabric settings
env.output_prefix = False
env.abort_on_prompts = True

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

def get_instances_with_filter(tag_filters, allowed_states=['pending', 'running', 'shutting-down', 'stopping', 'stopped']):
    """ Produces a list of instances based on a set of provided filters """
    ec2_client = boto3.client('ec2')

    instance_res = ec2_client.describe_instances(Filters=tag_filters +
        [{'Name': 'instance-state-name', 'Values' : allowed_states}]
    )['Reservations']

    instances = []
    # Collect all instances across all reservations
    if instance_res:
        for res in instance_res:
            if res['Instances']:
                instances.extend(res['Instances'])
    return instances

def get_manager_instance(tag_value):
    """ Looks up the manager instance dict using the CI run's unique tag"""
    instances = get_instances_with_filter([get_ci_filter(tag_value), manager_filter])
    if instances:
        assert len(instances) == 1
        return instances[0]
    else:
        return None

def get_manager_instance_id(tag_value):
    """ Looks up the manager instance ID using the CI run's unique tag"""

    manager = get_manager_instance(tag_value)
    if manager is None:
        print("No manager instance running with tag matching the assigned workflow id\n")
        sys.exit(1)
    else:
        return manager['InstanceId']

def get_manager_ip(tag_value):
    """ Looks up the manager IP using the CI run's unique tag"""

    manager = get_manager_instance(tag_value)
    if manager is None:
        print("No manager instance running with tag matching the assigned workflow id\n")
        sys.exit(1)
    else:
        return manager['PublicIpAddress']

def manager_hostname(tag_value):
    return "centos@{}".format(get_manager_ip(tag_value))

def get_all_workflow_instances(tag_value):
    """ Grabs a list of all instance dicts sharing the CI run's unique tag """
    return get_instances_with_filter([get_ci_filter(tag_value)])

def wait_on_instance(instance_id):
    """ Blocks on EC2 instance boot """
    ec2_client = boto3.client('ec2')
    waiter = ec2_client.get_waiter('instance_status_ok')
    waiter.wait(InstanceIds=[instance_id])

def instance_metadata_str(instance):
    """ Pretty prints instance info, including ID, state, and public IP """
    static_md = """    Instance ID: {}
    Instance State: {}""".format(instance['InstanceId'], instance['State']['Name'])

    dynamic_md = ""

    if instance.get('PublicIpAddress') is not None:
        dynamic_md = """
    Instance IP: {}""".format(instance['PublicIpAddress'])

    return static_md + dynamic_md


def change_workflow_instance_states(tag_value, state_change, dryrun=False):
    """ Change the state of all instances sharing the same CI run's tag. """

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
        client.terminate_instances(InstanceIds=instance_ids, DryRun=dryrun)
    else:
        raise ValueError("Unrecognized transition type: {}".format(state_change))

def terminate_workflow_instances(tag_value, dryrun=False):
    change_workflow_instance_states(tag_value, "terminate", dryrun)

def stop_workflow_instances(tag_value, dryrun=False):
    change_workflow_instance_states(tag_value, "stop", dryrun)

def start_workflow_instances(tag_value, dryrun=False):
    change_workflow_instance_states(tag_value, "start", dryrun)
