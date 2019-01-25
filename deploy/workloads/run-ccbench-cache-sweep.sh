#!/usr/bin/env bash

# the runfarm WILL be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim -c workloads/ccbench-cache-sweep.ini launchrunfarm
fi

firesim -c workloads/ccbench-cache-sweep.ini infrasetup
firesim -c workloads/ccbench-cache-sweep.ini runworkload
firesim -c workloads/ccbench-cache-sweep.ini terminaterunfarm --forceterminate

