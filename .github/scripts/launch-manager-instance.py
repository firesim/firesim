#!/usr/bin/env python3

# Used to launch a fresh manager instance from the CI container.

import sys

# This must run in the CI container
from ci_variables import ci_env
from platform_lib import Platform
from common import get_platform_lib

# Reuse manager utilities
sys.path.append(ci_env['GITHUB_WORKSPACE'] + "/deploy")
import awstools.awstools

def main():
    """ Spins up a new manager instance for our CI run """
    aws_platform_lib = get_platform_lib(Platform.AWS)

    if aws_platform_lib.check_manager_exists(ci_env['GITHUB_RUN_ID']):
        print("There is an existing manager instance for this CI workflow:")
        print(aws_platform_lib.get_manager_metadata_string(ci_env['GITHUB_RUN_ID']))
        sys.exit(0)

    print("Launching a fresh manager instance. This will take a couple minutes")
    awstools.awstools.main([
        'launch',
        '--inst_type', 'z1d.2xlarge',
        '--market', 'spot',
        '--int_behavior', 'terminate',
        '--block_devices', str([{'DeviceName':'/dev/sda1','Ebs':{'VolumeSize':300,'VolumeType':'gp2'}}]),
        '--tags', str(aws_platform_lib.get_manager_tag_dict(ci_env['GITHUB_SHA'], ci_env['GITHUB_RUN_ID'])),
        '--user_data_file', ci_env['GITHUB_WORKSPACE'] + "/scripts/machine-launch-script.sh"
    ])

    print("Instance ready.")
    print(aws_platform_lib.get_manager_metadata_string(ci_env['GITHUB_RUN_ID']))
    sys.stdout.flush()

if __name__ == "__main__":
    main()
