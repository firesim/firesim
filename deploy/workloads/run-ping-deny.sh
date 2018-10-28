#!/usr/bin/env bash

# run the ping-deny test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim launchrunfarm -c workloads/ping-deny-config.ini
fi

ORIGDIR=$(pwd)

cd ../results-workload

# make sure we don't get the same name as one of the manager produced results
# directories
sleep 2

firesim infrasetup -c workloads/ping-deny-config.ini
firesim runworkload -c workloads/ping-deny-config.ini --overrideconfigdata "targetconfig linklatency 31997"
firesim terminaterunfarm -c workloads/ping-latency-config.ini --forceterminate
