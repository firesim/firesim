#!/usr/bin/env bash

# Run type checking on manager Python files

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
FSIM_DIR=$SCRIPT_DIR/..

mypy --no-incremental \
    $FSIM_DIR/deploy/awstools/ \
    $FSIM_DIR/deploy/buildtools/ \
    $FSIM_DIR/deploy/runtools/ \
    $FSIM_DIR/deploy/util/ \
    $FSIM_DIR/deploy/firesim
