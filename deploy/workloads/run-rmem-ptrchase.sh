#!/bin/bash

trap "exit" INT
set -e
set -o pipefail

cd ../results-workload

resultsdir=$(date +"%Y-%m-%d--%H-%M-%S")-rmem-ptrchase-aggregate
mkdir $resultsdir

sleep 2

#latencies=(780 1530 2280 3030 3780 4530 5280 6030)
latencies=(30 780 1530 2280 3030 3780 4530 5280 6030)
#latencies=(180 330 480 630)
#latencies=(168 171 174 177)
#latencies=(178 179 180)

for lat in "${latencies[@]}"
do
    firesim infrasetup
    firesim runworkload --overrideconfigdata "targetconfig switchinglatency $lat"

    files=(*rmem-ptrchase)
    originalfilename=${files[-1]}
    mv $originalfilename $resultsdir/$lat
    cat $resultsdir/$lat/rmem-ptrchase/report.csv >> $resultsdir/aggregate-report.csv
done

echo "Aggregate Data in $PWD/$resultsdir"
