#!/usr/bin/env python

import sys

from fabric.api import *

# This is expected to be launched from the ci container
from ci_variables import *
from common import *

# Reuse manager utilities
sys.path.append(ci_workdir + "/deploy/awstools")
import awstools

INSTANCE_TYPE='c5.4xlarge'
MARKET_TYPE='ondemand'
SPOT_INT_BEHAVIOR='terminate'
SPOT_MAX_PRICE='ondemand'

def main():
    manager_instance = get_manager_instance()
    if (manager_instance is not None):
        print("There is an existing manager instance for this CI workflow:")
        print(instance_metadata_str(manager_instance))
        sys.exit(0)


    manager_instance = awstools.launch_instances(
        instancetype = INSTANCE_TYPE,
        count = 1,
        instancemarket = MARKET_TYPE,
        spotinterruptionbehavior = SPOT_INT_BEHAVIOR,
        spotmaxprice = SPOT_MAX_PRICE,
        blockdevices=[
            {
                'DeviceName': '/dev/sda1',
                'Ebs': {
                    'VolumeSize': 300,
                    'VolumeType': 'gp2',
                },
            }],
        tags=manager_tag_dict)[0]

    print("Launching a fresh manager instance.")
    manager_instance.wait_until_running()
    manager_instance.load()
    print("Waiting for instance initialization. This will take a couple minutes.")
    wait_on_instance(manager_instance.instance_id)
    print("Instance ready.")
    print(instance_metadata_str(get_manager_instance()))
    sys.stdout.flush()

if __name__ == "__main__":
    main()
