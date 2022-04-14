#!/usr/bin/env bash

# run the bw test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim launchrunfarm -c workloads/bw-test-config.yaml
fi

ORIGDIR=$(pwd)
cd ../../
git apply $ORIGDIR/bw-test-two-instances/switchpatch.patch
cd $ORIGDIR

bandwidths=( 1 10 40 100 )

cd ../results-workload

# create the aggregate results directory
resultsdir=$(date +"%Y-%m-%d--%H-%M-%S")-bw-test-aggregate
mkdir $resultsdir

# make sure we don't get the same name as one of the manager produced results
# directories
sleep 2

for i in "${bandwidths[@]}"
do
    firesim infrasetup -c workloads/bw-test-config.yaml
    firesim runworkload -c workloads/bw-test-config.yaml --overrideconfigdata "target_config net_bandwidth $i"
    # rename the output directory with the net bandwidth
    files=(*bw-test*)
    originalfilename=${files[-1]}
    mv $originalfilename $resultsdir/$i
done

python3 $ORIGDIR/bw-test-two-instances/bw-test-graph.py $(pwd)/$resultsdir

cd $ORIGDIR
cd ../../
git apply -R $ORIGDIR/bw-test-two-instances/switchpatch.patch

firesim terminaterunfarm -c workloads/bw-test-config.yaml --forceterminate

