#!/bin/bash

CL_DIR=$1

cd $CL_DIR/../../../../.. # aws-fpga
source hdk_setup.sh
export CL_DIR=$CL_DIR
cd $CL_DIR/build/scripts
./aws_build_dcp_from_cl.sh -foreground
