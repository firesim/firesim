#!/usr/bin/env python3

""" This script configures your AWS account to run FireSim. """

# contents of the script moved into deploy/awstools to make it easier
# to reuse for testing purposes
import os, sys
sys.path.append(os.path.join(os.path.dirname(__file__), "../deploy"))

from awstools.aws_setup import aws_setup
aws_setup()
