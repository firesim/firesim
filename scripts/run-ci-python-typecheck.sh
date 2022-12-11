#!/usr/bin/env bash

# Run type checking on CI Python files

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
FSIM_DIR=$SCRIPT_DIR/..

mypy --no-incremental \
    $FSIM_DIR/deploy/awstools \
    $FSIM_DIR/.github/scripts
