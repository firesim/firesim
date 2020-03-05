#!/usr/bin/env bash

# This script sets up VCS for FPGA-level RTL simulation on the Millennium
# cluster at Berkeley

firesim_root=$(pwd)/../
cwd=$(pwd)

source /ecad/tools/xilinx/Vivado/2018.2/settings64.sh > /dev/null
# ^ mangles your path; we have to put the native toolchain back in front
export PATH=/usr/bin:$PATH
# Need VCSMX for mixed language support. Warning: VCSMX does not work with our
# MIDAS-level RTL simulation flow
export VCS_HOME=/ecad/tools/synopsys/vcs-mx/O-2018.09-SP1
export PATH=$VCS_HOME/bin:$PATH

cd $firesim_root/platforms/f1/aws-fpga/ && source hdk_setup.sh
cd $cwd
