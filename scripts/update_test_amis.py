#!/usr/bin/env python3

# firesim tweaked version of https://raw.githubusercontent.com/spulec/moto/871591f7f17458c10ce039d6e2f36abe3430fec5/scripts/get_amis.py

import boto3
import json
import sure

import os, sys
deploy_path = os.path.join(os.path.dirname(__file__), "../deploy")
sys.path.append(deploy_path)

from awstools.awstools import get_f1_ami_id

our_ami = get_f1_ami_id()
print(f"Current FPGA Dev AMI used by firesim is {our_ami}")

instances = [
    our_ami,
]

client = boto3.client("ec2")

test = client.describe_images(ImageIds=instances)
test["Images"].shouldnt.be.empty

result = []
for image in test["Images"]:
    try:
        tmp = {
            "ami_id": image["ImageId"],
            "name": image["Name"],
            "description": image["Description"],
            "owner_id": image["OwnerId"],
            "public": image["Public"],
            "virtualization_type": image["VirtualizationType"],
            "architecture": image["Architecture"],
            "state": image["State"],
            "platform": image.get("Platform"),
            "image_type": image["ImageType"],
            "hypervisor": image["Hypervisor"],
            "root_device_name": image["RootDeviceName"],
            "root_device_type": image["RootDeviceType"],
            "sriov": image.get("SriovNetSupport", "simple"),
        }
        result.append(tmp)
    except Exception as err:
        raise # we can't afford to ignore any AMIs in our list, we need them all to be available

test_ami_path = os.path.join(deploy_path, "tests/test_amis.json")
with open(test_ami_path, 'w') as test_amis:
    json.dump(result, test_amis, indent=2)

print(f"Updated {test_ami_path}")
os.system(f"git diff {test_ami_path}")
