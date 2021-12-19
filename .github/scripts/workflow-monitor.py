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

import http.client
import time
import sys
import json

from common import terminate_workflow_instances, stop_workflow_instances

# Time between HTTPS requests to github
POLLING_INTERVAL_SECONDS = 60
# Number of failed requests before stopping the instances
QUERY_FAILURE_THRESHOLD = 10

# We should never get to 'not_run' or 'unauthorized' but terminate for good measure
TERMINATE_STATES = ["cancelled", "success", "skipped", "stale"]
STOP_STATES = ["failure", "timed_out"]
NOP_STATES = ["action_required"] # TODO: unsure when this happens

def main(workflow_id, gha_ci_token, gha_ci_personal_token):

    state = None
    consecutive_failures = 0
    headers = {'Authorization': "token {}".format(gha_ci_token.strip())}

    while True:
        time.sleep(POLLING_INTERVAL_SECONDS)

        conn = http.client.HTTPSConnection("api.github.com")
        # TODO: determine from env. vars
        owner = "firesim"
        repo = "firesim"
        conn.request("GET", "/repos/{}/{}/actions/runs/{}".format(owner, repo, workflow_id), headers=headers)

        res = conn.getresponse()

        if res.status == http.client.OK:
            consecutive_failures = 0
            res_dict = json.load(res)
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
            elif state_status not in ['in_progress', 'queued']:
                print("Unexpected Workflow State: {}".format(state_status))
                raise ValueError

        else:
            print("HTTP GET error: {} {}. Retrying.".format(res.status, res.reason))
            consecutive_failures = consecutive_failures + 1
            if consecutive_failures == QUERY_FAILURE_THRESHOLD:
                stop_workflow_instances(gha_ci_personal_token, workflow_id)
                exit(1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
