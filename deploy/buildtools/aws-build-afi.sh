#!/bin/bash

FIRESIM_DIR=
CHISEL_TRIPLET=

echo "Generating AGFI for $CHISEL_TRIPLET"

AWS_FPGA_DIR=$FIRESIM_DIR/platforms/f1/aws-fpga
DEV_DESIGN_DIR=$AWS_FPGA_DIR/hdk/cl/developer_designs
CL_DIR=$DEV_DESIGN_DIR/cl_$CHISEL_TRIPLET

# run ami build
cd $AWS_FPGA_DIR
source hdk_setup.sh
export CL_DIR=$CL_DIR
cd $CL_DIR/build/scripts
./aws_build_dcp_from_cl.sh -foreground
