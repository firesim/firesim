import boto3
import sys
from fabric.api import *

# Remote paths
manager_home_dir = "/home/centos"
manager_fsim_dir = "/home/centos/firesim"
manager_ci_dir   = manager_fsim_dir + "/.circleci"

# Common fabric settings
env.output_prefix=False

# This tag is common to all instances launched as part of a given workflow
unique_tag_key='ci-workflow-id'

# Produce a filter that return all instances associated with this workflow
def get_ci_filter(tag_value):
    return {'Name': 'tag:' + unique_tag_key, 'Values' : [tag_value]}

# Managers additionally have this empty tag defined
manager_filter = {'Name': 'tag:ci-manager', 'Values' : ['']}

def get_manager_tag_dict(sha, ci_workflow_id):
    """ Populates a set of tags for the manager of our CI run """
    return {
        'ci-commit-sha1': sha,
        'ci-manager':'',
        unique_tag_key: ci_workflow_id}

def get_instances_with_filter(tag_filters):
    """ Produces a list of instances based on a set of provided filters """
    ec2_client = boto3.client('ec2')

    instance_res = ec2_client.describe_instances(Filters = tag_filters +
        [{'Name': 'instance-state-name', 'Values' : ['pending','running','shutting-down','stopping','stopped']},
    ])['Reservations']

    if (len(instance_res) > 0 and len(instance_res[0]['Instances']) > 0):
        return instance_res[0]['Instances']
    else:
        return None

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

    if (instance.get('PublicIpAddress') is not None):
        dynamic_md = """
    Instance IP: {}""".format(instance['PublicIpAddress'])

    return static_md + dynamic_md

