#!/usr/bin/env python3

# Used to launch a fresh manager instance from the CI container.

import sys

# This must run in the CI container
from ci_variables import *
from common import *

# Reuse manager utilities
sys.path.append(ci_workdir + "/deploy/awstools")
import awstools

def main():
    """ Spins up a new manager instance for our CI run """
    manager_instance = get_manager_instance(ci_workflow_run_id)
    if manager_instance is not None:
        print("There is an existing manager instance for this CI workflow:")
        print(instance_metadata_str(manager_instance))
        sys.exit(0)

    print("Launching a fresh manager instance. This will take a couple minutes")
    awstools.main([
        'launch',
        '--inst_type', 'z1d.2xlarge',
        '--block_devices', str([{'DeviceName':'/dev/sda1','Ebs':{'VolumeSize':300,'VolumeType':'gp2'}}]),
        '--tags', str(get_manager_tag_dict(ci_commit_sha1, ci_workflow_run_id)),
        '--user_data_file', ci_workdir + "/scripts/machine-launch-script.sh"
    ])
    manager_instance = get_manager_instance(ci_workflow_run_id)

    print("Instance ready.")
    print(instance_metadata_str(manager_instance))
    sys.stdout.flush()

if __name__ == "__main__":
    main()
