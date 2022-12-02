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
import argparse
import requests
import traceback

from common import get_platform_lib
from github_common import gha_runs_api_url, issue_post, get_header, gha_workflow_api_url
from platform_lib import Platform, get_platform_enum
from ci_variables import ci_env

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

def wrap_in_code(wrap: str):
    return f"\n```\n{wrap}\n```"

def main(platform: Platform):
    consecutive_failures = 0

    print("Workflow monitor started")
    platform_lib = get_platform_lib(platform)

    try:
        print("Sending startup message")
        issue_post(ci_env['PERSONAL_ACCESS_TOKEN'], f"Workflow monitor started for CI run: {ci_env['GITHUB_RUN_ID']}")

        print("Beginning polling loop")
        while True:
            time.sleep(POLLING_INTERVAL_SECONDS)

            res = requests.get(gha_workflow_api_url, headers=get_header(ci_env['PERSONAL_ACCESS_TOKEN']))
            if res.status_code == 200:
                consecutive_failures = 0
                res_dict = res.json()
                state_status = res_dict["status"]
                state_concl = res_dict["conclusion"]

                print(f"Workflow {ci_env['GITHUB_RUN_ID']} status: {state_status} {state_concl}")

                # check that select instances are terminated on time
                platform_lib.check_and_terminate_select_instances(45, ci_env['GITHUB_RUN_ID'])

                if state_status in ['completed']:
                    if state_concl in TERMINATE_STATES:
                        platform_lib.terminate_instances(ci_env['PERSONAL_ACCESS_TOKEN'], ci_env['GITHUB_RUN_ID'])
                        return
                    elif state_concl in STOP_STATES:
                        platform_lib.stop_instances(ci_env['PERSONAL_ACCESS_TOKEN'], ci_env['GITHUB_RUN_ID'])
                        return
                    elif state_concl not in NOP_STATES:
                        print(f"Unexpected Workflow State On Completed: {state_concl}")
                        raise ValueError
                elif state_status not in ['in_progress', 'queued', 'waiting', 'requested']:
                    print(f"Unexpected Workflow State: {state_status}")
                    raise ValueError

            else:
                print(f"HTTP GET error: {res.json()}. Retrying.")
                consecutive_failures = consecutive_failures + 1
                if consecutive_failures == QUERY_FAILURE_THRESHOLD:
                    print("Consecutive HTTP GET errors. Terminating and exiting.")
                    platform_lib.terminate_instances(ci_env['PERSONAL_ACCESS_TOKEN'], ci_env['GITHUB_RUN_ID'])
                    raise ValueError
    except BaseException as e:
        post_str  = f"Something went wrong in the workflow monitor for CI run {ci_env['GITHUB_RUN_ID']}. Verify CI instances are terminated properly. Must be checked before submitting the PR.\n\n"
        post_str += f"**Exception Message:**{wrap_in_code(e)}\n"
        post_str += f"**Traceback Message:**{wrap_in_code(traceback.format_exc())}"

        issue_post(ci_env['PERSONAL_ACCESS_TOKEN'], post_str)

        platform_lib.check_and_terminate_select_instances(0, ci_env['GITHUB_RUN_ID'])
        platform_lib.terminate_instances(ci_env['PERSONAL_ACCESS_TOKEN'], ci_env['GITHUB_RUN_ID'])

        post_str = f"Instances for CI run {ci_env['GITHUB_RUN_ID']} were supposedly terminated. Verify termination manually.\n"

        issue_post(ci_env['PERSONAL_ACCESS_TOKEN'], post_str)

        exit(1)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()

    platform_choices = [str(p) for p in Platform]
    parser.add_argument('platform',
                        choices = platform_choices,
                        help = "The platform CI is being run on")
    args = parser.parse_args()
    platform = get_platform_enum(args.platform)

    main(platform)
