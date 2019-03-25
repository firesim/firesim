#!/bin/bash
set -x

cd root/qsort
/usr/bin/time -f "%S,%M,%F" ./qsort 10000 2> ../run_result.csv
poweroff
