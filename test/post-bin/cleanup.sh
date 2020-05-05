#!/bin/bash
set -e

# WARNING: This script looks weird because it's a post_run_hook. These scripts
# will be called by marshal like so:
# ./cleanup.sh ARGS path/to/output
#
# Consequently, we ignore the last argument

# This gives all but the last argument (bash only)
realArgs=${@:1:$#-1}

echo "Calling cleanup.sh as a post_run_hook: the last argument will be ignored"
echo "Cleaning up: " $realArgs
echo "Ignoring " "${!#}"
rm $realArgs
