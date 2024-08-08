#!/usr/bin/env bash

CUR_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
REQS_DIR="$CUR_DIR/../conda-reqs"
if [ ! -d "$REQS_DIR" ]; then
  echo "$REQS_DIR does not exist, make sure you're calling this script from firesim/"
  exit 1
fi

if ! conda-lock --version | grep $(grep "conda-lock" $REQS_DIR/firesim.yaml | sed 's/^ \+-.*=//'); then
  echo "Invalid conda-lock version, make sure you're calling this script with the sourced chipyard env.sh"
  exit 1
fi

rm -rf "$REQS_DIR/conda-reqs.conda-lock.yml"
conda-lock \
  --no-mamba \
  --no-micromamba \
  -f "$REQS_DIR/firesim.yaml" \
  -f "$REQS_DIR/ci-shared.yaml" \
  -p linux-64 \
  --lockfile "$REQS_DIR/conda-reqs.conda-lock.yml"
