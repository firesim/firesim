#!/usr/bin/env bash

max_runtime_seconds=$1
timeout_seconds=$2
ci_workflow_id=$3

# Set the upper bound bound
(sleep ${max_runtime_seconds}; ./terminate-workflow-instances.py $ci_workflow_id) &

inotify_rc=0
while [[ inotify_rc -eq 0 ]]; do
    sleep $timeout_seconds
    inotifywait -t $timeout_seconds -r -q -q ~/firesim/deploy/logs/ ~/firesim/sim/
    inotify_rc=$?
done

./terminate-workflow-instances.py $ci_workflow_id
