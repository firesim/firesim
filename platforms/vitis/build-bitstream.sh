#!/bin/bash

# This script is called by FireSim's bitbuilder to create a xclbin

set -e

CL_DIR=$1

# run build
cd $CL_DIR
make bitstream
