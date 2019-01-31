#!/usr/bin/env bash

# run the bw test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

#if [ "$1" == "withlaunch" ]; then
#    firesim launchrunfarm -c workloads/unittest/flash-stress-config.ini
#fi

COUNTER=1

echo "start at" >> STRESSRUNS
date >> STRESSRUNS

while [ $COUNTER -gt 0 ]; do
    firesim launchrunfarm -c workloads/unittest/flash-stress-config.ini
    firesim infrasetup -c workloads/unittest/flash-stress-config.ini
    firesim runworkload -c workloads/unittest/flash-stress-config.ini
    firesim terminaterunfarm -c workloads/unittest/flash-stress-config.ini --forceterminate
    echo "done $COUNTER"
    echo "done $COUNTER" >> STRESSRUNS
    date >> STRESSRUNS
    let COUNTER=COUNTER+1
done

