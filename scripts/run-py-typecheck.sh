#!/usr/bin/env bash

# Run type checking on manager Python files

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
DEPLOY_DIR=$SCRIPT_DIR/../deploy

export MYPYPATH="$DEPLOY_DIR"/stubs

mypy --namespace-packages --no-incremental $DEPLOY_DIR/awstools/ $DEPLOY_DIR/buildtools/ $DEPLOY_DIR/runtools/ $DEPLOY_DIR/util/ $DEPLOY_DIR/firesim
