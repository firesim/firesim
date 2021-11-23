#!/bin/bash

set -e

CL_DIR=$1

# run build
cd $CL_DIR
make bitstream
