#!/usr/bin/env python3

# Runs in the background on a manager instance to determine when it can be torn
# down by polling the workflow's state using GitHub Actions's RESTful api.
#
# Site: https://docs.github.com/en/rest/reference/actions#get-a-workflow-run
#
# Terminate instances if:
#   - the workflow is successful (all jobs complete successfully)
#   - the workflow is cancelled
# Stop instances if:
#   - the workflow has failed (all jobs have completed, but at least one has failed)
#
# Other states to consider: not_run, on_hold, unauthorized

import time
import sys
import argparse
import requests

from common import get_platform_lib, gha_runs_api_url
from platform_lib import Platform, get_platform_enum
# Time between HTTPS requests to github
POLLING_INTERVAL_SECONDS = 60
# Number of failed requests before stopping the instances
QUERY_FAILURE_THRESHOLD = 10

TERMINATE_STATES = ["cancelled", "success", "skipped", "stale", "failure", "timed_out"]
# In the past we'd stop instances on failure or time-out conditions so that
# they could be restarted and debugged in-situ. This was mostly useful for CI dev.
# See discussion in: https://github.com/firesim/firesim/pull/1037
STOP_STATES = []
NOP_STATES = ["action_required"] # TODO: unsure when this happens
    
def main(platform: Platform, workflow_id: str, gha_ci_personal_token: str):

    consecutive_failures = 0
    headers = {'Authorization': "token {}".format(gha_ci_personal_token.strip())}
    gha_workflow_api_url = f"{gha_runs_api_url}/{workflow_id}"

    platform_lib = get_platform_lib(platform)
    while True:
        time.sleep(POLLING_INTERVAL_SECONDS)

        res = requests.get(gha_workflow_api_url, headers=headers)
        if res.status_code == 200:
            consecutive_failures = 0
            res_dict = res.json()
            state_status = res_dict["status"]
            state_concl = res_dict["conclusion"]

            print("Workflow {} status: {} {}".format(workflow_id, state_status, state_concl))
            if state_status in ['completed']:
                if state_concl in TERMINATE_STATES:
                    platform_lib.terminate_instances(gha_ci_personal_token, workflow_id)
                    exit(0)
                elif state_concl in STOP_STATES:
                    platform_lib.stop_instances(gha_ci_personal_token, workflow_id)
                    exit(0)
                elif state_concl not in NOP_STATES:
                    print("Unexpected Workflow State On Completed: {}".format(state_concl))
                    raise ValueError
            elif state_status not in ['in_progress', 'queued', 'waiting', 'requested']:
                print("Unexpected Workflow State: {}".format(state_status))
                raise ValueError

        else:
            print("HTTP GET error: {}. Retrying.".format(res.json()))
            consecutive_failures = consecutive_failures + 1
            if consecutive_failures == QUERY_FAILURE_THRESHOLD:
                platform_lib.stop_instances(gha_ci_personal_token, workflow_id)
                exit(1)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    
    platform_choices = [str(p) for p in Platform]
    parser.add_argument('platform',
                        choices = platform_choices,
                        help = "The platform CI is being run on")
    parser.add_argument('workflow_id',
                        help='ID of the workflow that is used to query the CI instance')
    parser.add_argument('gha_ci_personal_token',
                        help="GitHub personal access token used to query GitHub REST API")
    args = parser.parse_args()
    platform = get_platform_enum(args.platform)

    main(platform, args.workflow_id, args.gha_ci_personal_token)
