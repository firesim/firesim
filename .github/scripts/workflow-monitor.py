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
import requests

from common import terminate_workflow_instances, stop_workflow_instances

# Time between HTTPS requests to github
POLLING_INTERVAL_SECONDS = 60
# Number of failed requests before stopping the instances
QUERY_FAILURE_THRESHOLD = 10

# We should never get to 'not_run' or 'unauthorized' but terminate for good measure
TERMINATE_STATES = ["cancelled", "success", "skipped", "stale"]
STOP_STATES = ["failure", "timed_out"]
NOP_STATES = ["action_required"] # TODO: unsure when this happens

def main(workflow_id, gha_ci_personal_token):

    state = None
    consecutive_failures = 0
    headers = {'Authorization': "token {}".format(gha_ci_personal_token.strip())}

    while True:
        time.sleep(POLLING_INTERVAL_SECONDS)

        res = requests.get("https://api.github.com/repos/firesim/firesim/actions/runs/{}".format(workflow_id), headers=headers)
        if res.status_code == 200:
            consecutive_failures = 0
            res_dict = res.json()
            state_status = res_dict["status"]
            state_concl = res_dict["conclusion"]

            print("Workflow {} status: {} {}".format(workflow_id, state_status, state_concl))
            if state_status in ['completed']:
                if state_concl in TERMINATE_STATES:
                    terminate_workflow_instances(gha_ci_personal_token, workflow_id)
                    exit(0)
                elif state_concl in STOP_STATES:
                    stop_workflow_instances(gha_ci_personal_token, workflow_id)
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
                stop_workflow_instances(gha_ci_personal_token, workflow_id)
                exit(1)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
