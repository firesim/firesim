#!/bin/bash
# Usage: ./check-output.sh UARTLOG BASE_DIR
#   - UARTLOG: path to uartlog to check
#   - BASE_DIR: cd here before running
#
# Will check for a fixed pattern in the uartlog of a run. Will touch a file
# called "SUCCESS" if the output matches, "FAILURE" otherwise. This is intended
# to be used as a post_run_hook to the sw_manager or firesim commands (hence
# the slightly odd arguments).

uartlog=$1
baseDir=$2

cd $baseDir
grep -q "Global : command" $uartlog
commandRes=$?

grep -q "Global : linux-src" $uartlog
linuxRes=$?

if [ $commandRes == 0 ] && [ $linuxRes == 0 ]; then
  touch SUCCESS
else
  touch FAILURE
fi
