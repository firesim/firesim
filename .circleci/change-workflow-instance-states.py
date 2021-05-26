#!/usr/bin/env python

# Changes the state of instances associated with the CI run's unique tag.
# Where a run is a workflow in CircleCI. Can be used to start, stop, or
# terminate.  This may run from either the manager or from the CI instance.

import sys
import argparse

import common

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('tag_value',
                       help = "The tag used to identify workflow instances.")
    parser.add_argument('state_change',
                       choices = ['terminate', 'stop', 'start'],
                       help = "The state transition to initiate on workflow instances.")

    args = parser.parse_args()
    common.change_workflow_instance_states(args.tag_value, args.state_change)
