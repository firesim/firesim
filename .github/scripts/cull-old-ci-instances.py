#!/usr/bin/env python3

# Runs periodically in it's own workflow in the CI/CD environment to teardown
# instances that have exceeded a lifetime limit

import datetime
import pytz
import boto3
import sys

from platform_lib import Platform, find_timed_out_resources
from common import get_platform_lib
from github_common import deregister_runners

# Reuse manager utilities
from ci_variables import ci_env
sys.path.append(ci_env['GITHUB_WORKSPACE'] + "/deploy")

# The number of hours a manager instance may exist since its initial launch time
INSTANCE_LIFETIME_LIMIT_HOURS = 8
# The number of hours a fpga instance may exist since its initial launch time
FPGA_INSTANCE_LIFETIME_LIMIT_HOURS = 1

def cull_aws_instances(current_time: datetime.datetime) -> None:
    # Grab all instances with a CI-generated tag
    aws_platform_lib = get_platform_lib(Platform.AWS)

    client = boto3.client('ec2')

    run_farm_ci_instances = aws_platform_lib.find_run_farm_ci_instances()
    instances_to_terminate = find_timed_out_resources(FPGA_INSTANCE_LIFETIME_LIMIT_HOURS * 60, current_time, map(lambda x: (x, x['LaunchTime']), run_farm_ci_instances))
    run_farm_instances_to_terminate = list(set(instances_to_terminate))
    print("Terminated Run Farm Instances:")
    for inst in run_farm_instances_to_terminate:
        client.terminate_instances(InstanceIds=[inst['InstanceId']])
        print("  " + inst['InstanceId'])

    all_ci_instances = aws_platform_lib.find_all_ci_instances()
    instances_to_terminate = find_timed_out_resources(INSTANCE_LIFETIME_LIMIT_HOURS * 60, current_time, map(lambda x: (x, x['LaunchTime']), all_ci_instances))
    manager_instances_to_terminate = list(set(instances_to_terminate))
    print("Terminated Manager Instances:")
    for inst in manager_instances_to_terminate:
        deregister_runners(ci_env['PERSONAL_ACCESS_TOKEN'], f"aws-{ci_env['GITHUB_RUN_ID']}")
        aws_platform_lib.platform_terminate_instances([inst['InstanceId']])
        print("  " + inst['InstanceId'])

    if len(manager_instances_to_terminate) > 0 or len(run_farm_instances_to_terminate) > 0:
        exit(1)

def cull_azure_resources(current_time: datetime.datetime) -> None:
    azure_platform_lib = get_platform_lib(Platform.AZURE)
    all_azure_ci_vms = azure_platform_lib.find_all_ci_instances()
    run_farm_azure_ci_vms = azure_platform_lib.find_run_farm_ci_instances()

    vms_to_terminate = find_timed_out_resources(FPGA_INSTANCE_LIFETIME_LIMIT_HOURS * 60, current_time, \
        map(lambda x: (x, datetime.datetime.strptime(x['LaunchTime'],'%Y-%m-%d %H:%M:%S.%f%z')), run_farm_azure_ci_vms))
    vms_to_terminate += find_timed_out_resources(INSTANCE_LIFETIME_LIMIT_HOURS * 60, current_time, \
        map(lambda x: (x, datetime.datetime.strptime(x['LaunchTime'],'%Y-%m-%d %H:%M:%S.%f%z')), all_azure_ci_vms))
    vms_to_terminate = list(set(vms_to_terminate))

    print("Terminated VMs:")
    for vm in vms_to_terminate:
        deregister_runners(ci_env['PERSONAL_ACCESS_TOKEN'], f"azure-{ci_env['GITHUB_RUN_ID']}")
        azure_platform_lib.platform_terminate_instances([vm]) # prints are handled in here

    if len(vms_to_terminate) > 0:
        exit(1)

def main():
    # Get a timezone-aware datetime instance
    current_time = datetime.datetime.utcnow().replace(tzinfo=pytz.UTC)

    cull_aws_instances(current_time)
    #cull_azure_resources(current_time)

if __name__ == "__main__":
    main()
