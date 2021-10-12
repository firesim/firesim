#!/bin/bash

set -ex

CL_DIR=$1
AWS_FPGA_DIR=$CL_DIR/../../../..

cd $AWS_FPGA_DIR
source hdk_setup.sh
export CL_DIR=$CL_DIR
cd $CL_DIR/build/scripts
./aws_build_dcp_from_cl.sh -foreground
