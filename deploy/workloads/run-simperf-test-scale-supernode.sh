#!/usr/bin/env bash

# IMPORTANT! availability zone placement MATTERS for simulation performance.
# Usually, you will not be able to get 32 nodes in one availability zone,
# but frequently you will be able to get 16. The way the manager currently
# terminates nodes is based on their sorted ip address, which may means you
# end up killing some from different avail zones, even though it would be
# ideal to completely remove nodes from "extra" availability zones.
#
# Until this is fixed in the manager, you should run this script separately to:
# 1) get the 256 node result by commenting out the rest of the calls to loopfunc
# 2) terminate your run farm, then launch a new one (where all 16 hosts +
# switches will likely be in the same availability zone) that supports only 128
# nodes and below, then run from there, decreasing scale.

# run the simperf SCALE poweroff test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim launchrunfarm -c workloads/simperf-test-scale-supernode-config.ini
fi

ORIGDIR=$(pwd)

cd ../results-workload

# create the aggregate results directory
resultsdir=$(date +"%Y-%m-%d--%H-%M-%S")-simperf-test-scale-supernode-aggregate
mkdir $resultsdir

# make sure we don't get the same name as one of the manager produced results
# directories
sleep 2

loopfunc () {
    echo "RUNNING supernode_example_$1config"
    # arg 1 is num nodes
    # arg 2 is num f116xlarges to kill AFTERWARDS
    # arg 3 is num m416xlarges to kill AFTERWARDS
    firesim infrasetup -c workloads/simperf-test-scale-supernode-config.ini --overrideconfigdata "targetconfig topology supernode_example_$1config"
    firesim runworkload -c workloads/simperf-test-scale-supernode-config.ini --overrideconfigdata "targetconfig topology supernode_example_$1config"
    # rename the output directory with the ping latency
    files=(*simperf-test-scale*)
    originalfilename=${files[-1]}
    mv $originalfilename $resultsdir/$1

    firesim terminaterunfarm -c workloads/simperf-test-scale-supernode-config.ini --terminatesomef116 $2 --terminatesomem416 $3 --forceterminate

}

loopfunc 1024 16 2

loopfunc 512 8 2

loopfunc 256 4 0

loopfunc 128 2 0

loopfunc 64 1 1

loopfunc 32 0 0

loopfunc 16 0 0

loopfunc 8 0 0

loopfunc 4 1 0

python $ORIGDIR/simperf-test-scale/simperf-test-scale-results.py $(pwd)/$resultsdir
