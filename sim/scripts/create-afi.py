#!/usr/bin/env python3

import os.path
import argparse
import subprocess
import time

parser = argparse.ArgumentParser(
  description = "Create AFI for Amazon EC2 F1")
parser.add_argument(
  "-b", "--bucket", dest="bucket", type=str, help="bucket name", required=True)
parser.add_argument(
  "-t", "--tarball", dest="tarball", type=str, help="tarball file from build", required=True)
parser.add_argument(
  "-n", "--name", dest="name", type=str, help="AFI name", required=True)
parser.add_argument(
  "-d", "--description", dest="desc", type=str, help="AFI description")

args = parser.parse_args()

""" Upload the barball """
tarball_path = os.path.realpath(args.tarball)
subprocess.check_call("aws s3 cp %s s3://%s/dcp/" % (tarball_path, args.bucket), shell=True)

time.sleep(1)

""" Create FPGA image """
description = args.desc if args.desc else args.name
tarball_name = os.path.basename(tarball_path)
subprocess.check_call("""
  aws ec2 create-fpga-image \
  --name %s \
  --description %s \
  --input-storage-location Bucket=%s,Key=dcp/%s \
  --logs-storage-location Bucket=%s,Key=logs/
""" % (args.name, description, args.bucket, tarball_name, args.bucket), shell=True)
