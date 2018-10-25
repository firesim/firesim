#!/usr/bin/env bash

# run the ping-latency test using the manager. optionally passing "withlaunch" will also
# automatically launch the appropriate runfarm
#
# the runfarm WILL NOT be terminated upon completion

trap "exit" INT
set -e
set -o pipefail

if [ "$1" == "withlaunch" ]; then
    firesim launchrunfarm -c workloads/ping-latency-config.ini
fi

ORIGDIR=$(pwd)

latencies=( 70 3199 6405 9597 12803 16002 19201 22400 25599 28798 31997 )
# for script testing:
# latencies=( 31997 )

cd ../results-workload

# create the aggregate results directory
resultsdir=$(date +"%Y-%m-%d--%H-%M-%S")-ping-latency-aggregate
mkdir $resultsdir

# make sure we don't get the same name as one of the manager produced results
# directories
sleep 2

for i in "${latencies[@]}"
do
    firesim infrasetup -c workloads/ping-latency-config.ini
    firesim runworkload -c workloads/ping-latency-config.ini --overrideconfigdata "targetconfig linklatency $i"
    # rename the output directory with the ping latency
    files=(*ping-latency*)
    originalfilename=${files[-1]}
    mv $originalfilename $resultsdir/$i
done

python $ORIGDIR/ping-latency/ping-latency-graph.py $(pwd)/$resultsdir

firesim terminaterunfarm -c workloads/ping-latency-config.ini --forceterminate

