#!/usr/bin/env python

# Terminates all instances associated with the CI workflow ID, the first
# argument of this script.
# This may run from either the manager or from the CI instance.
# So don't assume we have access to particular CI env variables

import sys
import os

from common import *

# Reuse manager utilities
script_loc = os.path.dirname(os.path.abspath(__file__))
sys.path.append(script_loc + "/../deploy/awstools")
import awstools

def terminate_workflow_instances(tag):
    """ Terminate all instances with the ci_workflow_id matching the first argument """
    instances = get_instances_with_filter([get_ci_filter(tag)])

    for instance in instances:
        awstools.terminate_instances([instance['InstanceId']], dryrun=False)
        print("Terminating instance:")
        print(instance_metadata_str(instance))

if __name__ == "__main__":
    terminate_workflow_instances(sys.argv[1])
