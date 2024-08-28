#!/usr/bin/env bash

# Run type checking on manager Python files

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
FSIM_DIR=$SCRIPT_DIR/..

CHECK_FLAG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --check)
            CHECK_FLAG=$1
            ;;
        *)
            echo "Invalid Argument: $1"
            exit 1
            ;;
    esac
    shift
done

set -ex

black $CHECK_FLAG \
    $FSIM_DIR/deploy
