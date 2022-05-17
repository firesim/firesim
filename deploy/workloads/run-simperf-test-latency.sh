#!/usr/bin/env bash

# run the simperf poweroff test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim launchrunfarm -c workloads/simperf-test-latency-config.yaml
fi

ORIGDIR=$(pwd)

latencies=( 70 1603 3199 6405 9597 12803 16002 19201 22400 25599 28798 31997 )
# for script testing:
#latencies=( 3199 6405 9597 12803 16002 19201 22400 25599 28798 31997 )

cd ../results-workload

# create the aggregate results directory
resultsdir=$(date +"%Y-%m-%d--%H-%M-%S")-simperf-test-latency-aggregate
mkdir $resultsdir

# make sure we don't get the same name as one of the manager produced results
# directories
sleep 2

for i in "${latencies[@]}"
do
    firesim infrasetup -c workloads/simperf-test-latency-config.yaml
    firesim runworkload -c workloads/simperf-test-latency-config.yaml --overrideconfigdata "target_config link_latency $i"
    # rename the output directory with the ping latency
    files=(*simperf-test-latency*)
    originalfilename=${files[-1]}
    mv $originalfilename $resultsdir/$i
done

python3 $ORIGDIR/simperf-test-latency/simperf-test-results.py $(pwd)/$resultsdir
firesim terminaterunfarm -c workloads/simperf-test-latency-config.yaml --forceterminate


