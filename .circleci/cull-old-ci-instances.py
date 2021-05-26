#!/usr/bin/env python

# Runs periodically in it's own workflow in the CI/CD environment to teardown
# instances that have exceeded a lifetime limit

import datetime
import pytz
import boto3

from common import get_instances_with_filter, unique_tag_key

# The number of hours an instance may exist since its initial launch time
INSTANCE_LIFETIME_LIMIT_HOURS = 24

def main():
    # Get a timezone-aware datetime instance
    current_time = datetime.datetime.utcnow().replace(tzinfo=pytz.UTC)

    # Grab all instances with a CI-generated tag
    all_ci_instances_filter = {'Name': 'tag:' + unique_tag_key, 'Values' : ['*']}
    all_ci_instances = get_instances_with_filter([all_ci_instances_filter], allowed_states=['*'])

    client = boto3.client('ec2')
    print "Terminated Instances:"
    for inst in all_ci_instances:
        lifetime_secs = (current_time - inst["LaunchTime"]).total_seconds()
        if lifetime_secs > (INSTANCE_LIFETIME_LIMIT_HOURS * 3600):
            client.terminate_instances(InstanceIds=[inst["InstanceId"]])
            print "  " + inst["InstanceId"]

if __name__ == "__main__":
    main()
