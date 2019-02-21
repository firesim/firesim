#!/usr/bin/env bash

set -e
set -o pipefail

# build setup
./build-setup.sh fast
source sourceme-f1-manager.sh

# run through elaboration flow to get chisel/sbt all setup
cd sim
make f1

# build target software
cd ../sw/firesim-software
./marshal -v build workloads/br-base.json
