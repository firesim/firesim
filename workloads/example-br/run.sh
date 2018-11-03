#!/bin/bash

cd root/qsort
/usr/bin/time -f "%S,%M,%F" -v ./qsort -s 10000 > run_result.csv
sync
poweroff -f
