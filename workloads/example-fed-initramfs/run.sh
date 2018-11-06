#!/bin/bash
set -x

# This script will be run every time you boot the workload. In this case we're
# running a benchmark and recording some timing information into a log that can
# be extracted later.

# Note that we don't call poweroff at the end (unlike in example-fed). This is
# because there is no way to extract results from an initramfs-based system,
# you'll need to directly copy-paste from the command line. We can still use
# /usr/bin/time though because we based this workload off example-fed, which
# installed it.

cd /root/qsort
/usr/bin/time -f "%S,%M,%F" ./qsort 10000 2> ../run_result.csv
