#!/usr/bin/env python

# Runs in the background on a manager instance to determine when it can be torn
# down by polling the workflow's state using CircleCI's v2.0 restful api.
#
# Terminate instances if:
#   - the workflow is successful (all jobs complete successfully)
#   - the workflow is cancelled
# Stop instances if:
#   - the workflow has failed (all jobs have completed, but at least one has failed)
#
# Other states to consider: not_run, on_hold, unauthorized

import httplib
import time
import sys
import base64
import json

from common import terminate_workflow_instances, stop_workflow_instances

# Time between HTTPS requests to circleci
POLLING_INTERVAL_SECONDS = 60
# Number of failed requests before stopping the instances
QUERY_FAILURE_THRESHOLD = 10

# We should never get to 'not_run' or 'unauthorized' but terminate for good measure
TERMINATE_STATES = ['success', 'canceled', 'not_run', 'unauthorized']
STOP_STATES = ['failed', 'error']
NOP_STATES = ['running', 'failing']

def main(workflow_id, circle_ci_token):

    state = None
    consecutive_failures = 0
    auth_token = base64.b64encode(b"{}:", circle_ci_token)
    headers = {'authorization': "Basic {}".format(auth_token)}

    while True:
        time.sleep(POLLING_INTERVAL_SECONDS)

        conn = httplib.HTTPSConnection("circleci.com")
        conn.request("GET", "/api/v2/workflow/{}".format(workflow_id), headers=headers)

        res = conn.getresponse()

        if res.status == httplib.OK:
            consecutive_failures = 0
            res_dict = json.load(res)
            state = res_dict["status"]

            print "Workflow {} status: {}".format(workflow_id, state)
            if state in TERMINATE_STATES:
                terminate_workflow_instances(workflow_id)
                exit(0)
            elif state in STOP_STATES:
                stop_workflow_instances(workflow_id)
                exit(0)
            elif state not in NOP_STATES:
                print "Unexpected Workflow State: {}".format(state)
                raise ValueError

        else:
            print "HTTP GET error: {} {}. Retrying.".format(res.status, res.reason)
            consecutive_failures = consecutive_failures + 1
            if consecutive_failures == QUERY_FAILURE_THRESHOLD:
                stop_workflow_instances(workflow_id)
                exit(1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
