#!/usr/bin/env python3

# Changes the state of instances associated with the CI workflow run's unique tag.
# Can be used to start, stop, or terminate.  This may run from either the manager
# or from the CI instance.

import argparse

from common import change_workflow_instance_states

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('tag_value',
                       help = "The tag used to identify workflow instances.")
    parser.add_argument('state_change',
                       choices = ['terminate', 'stop', 'start'],
                       help = "The state transition to initiate on workflow instances.")
    parser.add_argument('github_api_token',
                       help = "API token to modify self-hosted runner state.")

    args = parser.parse_args()
    change_workflow_instance_states(args.github_api_token, args.tag_value, args.state_change)
