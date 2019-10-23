#!/bin/bash
set -x

# This script will be run every time you boot the workload. In this case we're
# running a benchmark and recording some timing information into a csv that can
# be extracted later. Also note that we call poweroff at the end, if you would
# prefer to interact with the workload after it's booted, you can leave that off. 

cd root/qsort
/usr/bin/time -f "%S,%M,%F" ./qsort 10000 2> ../run_result.csv
poweroff
