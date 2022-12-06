#!/usr/bin/env python3

# Changes the state of instances associated with the CI workflow run's unique tag.
# Can be used to start, stop, or terminate.  This may run from either the manager
# or from the CI instance.

import argparse

from platform_lib import Platform, get_platform_enum
from common import get_platform_lib

if __name__ == "__main__":
    parser = argparse.ArgumentParser()

    platform_choices = [str(p) for p in Platform]
    parser.add_argument('platform',
                        choices = platform_choices,
                        help = "The platform CI is being run on")
    parser.add_argument('workflow_tag',
                       help = "The tag used to identify workflow instances.")
    parser.add_argument('state_change',
                       choices = ['terminate', 'stop', 'start'],
                       help = "The state transition to initiate on workflow instances.")
    parser.add_argument('github_api_token',
                       help = "API token to modify self-hosted runner state.")
    args = parser.parse_args()
    platform = get_platform_enum(args.platform)
    if platform == Platform.AWS or platform == Platform.ALL:
        get_platform_lib(Platform.AWS).change_workflow_instance_states(args.github_api_token, args.workflow_tag, args.state_change)
    if platform == Platform.AZURE or platform == Platform.ALL:
        get_platform_lib(Platform.AZURE).change_workflow_instance_states(args.github_api_token, args.workflow_tag, args.state_change)
