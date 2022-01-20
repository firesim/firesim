#!/usr/bin/env python3

# Runs periodically in it's own workflow in the CI/CD environment to teardown
# instances that have exceeded a lifetime limit

import datetime
import pytz
import boto3
import sys

from common import unique_tag_key, deregister_runner_if_exists

# Reuse manager utilities
from ci_variables import ci_workdir, ci_personal_api_token, ci_workflow_run_id
sys.path.append(ci_workdir + "/deploy/awstools")
from awstools import get_instances_with_filter

# The number of hours an instance may exist since its initial launch time
INSTANCE_LIFETIME_LIMIT_HOURS = 24

def main():
    # Get a timezone-aware datetime instance
    current_time = datetime.datetime.utcnow().replace(tzinfo=pytz.UTC)

    # Grab all instances with a CI-generated tag
    all_ci_instances_filter = {'Name': 'tag:' + unique_tag_key, 'Values' : ['*']}
    all_ci_instances = get_instances_with_filter([all_ci_instances_filter], allowed_states=['*'])

    client = boto3.client('ec2')
    print("Terminated Instances:")
    for inst in all_ci_instances:
        lifetime_secs = (current_time - inst["LaunchTime"]).total_seconds()
        if lifetime_secs > (INSTANCE_LIFETIME_LIMIT_HOURS * 3600):
            deregister_runner_if_exists(ci_personal_api_token, ci_workflow_run_id):
            client.terminate_instances(InstanceIds=[inst["InstanceId"]])
            print("  " + inst["InstanceId"])

if __name__ == "__main__":
    main()
