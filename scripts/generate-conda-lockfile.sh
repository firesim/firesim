#!/usr/bin/env bash

CUR_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
REQS_DIR="$CUR_DIR/../conda-reqs"
if [ ! -d "$REQS_DIR" ]; then
  echo "$REQS_DIR does not exist, make sure you're calling this script from firesim/"
  exit 1
fi
conda-lock -f "$REQS_DIR/firesim.yaml" -f "$REQS_DIR/ci-shared.yaml" -p linux-64 --lockfile "$REQS_DIR/conda-reqs.conda-lock.yml"
