#!/usr/bin/env python3

# Runs periodically in it's own workflow in the CI/CD environment to teardown
# instances that have exceeded a lifetime limit

import datetime
from typing import Iterable, Tuple, Any
from xmlrpc.client import DateTime
import pytz
import boto3
import sys
from platform_lib import Platform
from common import deregister_runners, get_platform_lib

# Reuse manager utilities
from ci_variables import ci_workdir, ci_personal_api_token, ci_workflow_run_id, ci_azure_sub_id
sys.path.append(ci_workdir + "/deploy")

# The number of hours an instance may exist since its initial launch time
INSTANCE_LIFETIME_LIMIT_HOURS = 8

def find_timed_out_resources(current_time: DateTime, resource_list: Iterable[Tuple]) -> list:
    """ 
    Because of the differences in how AWS and Azure store time tags, the resource_list
    in this case is a list of tuples with the 0 index being the instance/vm and the 1 index
    a datetime object corresponding to the time
    """
    timed_out = []
    for resource_tuple in resource_list:
        lifetime_secs = (current_time - resource_tuple[1]).total_seconds()
        if lifetime_secs > (INSTANCE_LIFETIME_LIMIT_HOURS * 3600):
            timed_out.append(resource_tuple[0])
    return timed_out 

def cull_aws_instances(current_time: DateTime) -> None:
    # Grab all instances with a CI-generated tag
    aws_platform_lib = get_platform_lib(Platform.AWS)
    all_ci_instances = aws_platform_lib.find_all_ci_instances()
    
    client = boto3.client('ec2')

    instances_to_terminate = find_timed_out_resources(current_time, map(lambda x: (x, x['LaunchTime']), all_ci_instances))
    
    print("Terminated Instances:")
    for inst in instances_to_terminate:
        deregister_runners(ci_personal_api_token, f"aws-{ci_workflow_run_id}")
        client.terminate_instances(InstanceIds=[inst['InstanceId']])
        print("  " + inst['InstanceId'])

def cull_azure_resources(current_time: DateTime) -> None:
    azure_platform_lib = get_platform_lib(Platform.AZURE)
    all_azure_ci_vms = azure_platform_lib.find_all_ci_instances()

    vms_to_terminate = find_timed_out_resources(current_time, \
        map(lambda x: (x, datetime.datetime.strptime(x['LaunchTime'],'%Y-%m-%d %H:%M:%S.%f%z')), all_azure_ci_vms))
    
    print("VMs:")
    for vm in vms_to_terminate:
        deregister_runners(ci_personal_api_token, f"azure-{ci_workflow_run_id}")
        azure_platform_lib.terminate_azure_vms([vm]) #prints are handled in here

def main():
    # Get a timezone-aware datetime instance
    current_time = datetime.datetime.utcnow().replace(tzinfo=pytz.UTC)

    cull_aws_instances(current_time)
    #cull_azure_resources(current_time)

if __name__ == "__main__":
    main()
