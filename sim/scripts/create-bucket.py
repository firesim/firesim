#!/usr/bin/env python3

import os.path
import argparse
import subprocess

parser = argparse.ArgumentParser(
  description = "Create AFI for Amazon EC2 F1")
parser.add_argument(
  "-b", "--bucket", dest="bucket", type=str, help="bucket name", required=True)

args = parser.parse_args()

""" Create bucket """
subprocess.check_call("aws s3 mb s3://%s --region us-east-1" % (args.bucket), shell=True)
subprocess.check_call("aws s3 mb s3://%s/dcp" % (args.bucket), shell=True)
subprocess.check_call("aws s3 mb s3://%s/logs" % (args.bucket), shell=True)
with open("LOG_FILES_GO_HERE.txt", "w") as f:
  f.write("")
subprocess.check_call("aws s3 cp LOG_FILES_GO_HERE.txt s3://%s/logs/" % (args.bucket), shell=True)
