#!/bin/bash

set -e

CL_DIR=$1
shift # get rid of $1 arg or else hdk_setup.sh will fail
AWS_FPGA_DIR=$CL_DIR/../../../..

# setup hdk
cd $AWS_FPGA_DIR
source hdk_setup.sh

export CL_DIR=$CL_DIR

# run build
cd $CL_DIR/build/scripts
./aws_build_dcp_from_cl.sh -foreground
