#!/usr/bin/env bash

# run the memcached experiment using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim launchrunfarm -c workloads/memcached-thread-imbalance-config.ini
fi


firesim infrasetup -c workloads/memcached-thread-imbalance-config.ini
firesim runworkload -c workloads/memcached-thread-imbalance-config.ini
firesim terminaterunfarm -c workloads/memcached-thread-imbalance-config.ini --forceterminate

