#!/bin/bash
set -ex
# Argument 1: old vivado version sourceme
# Argument 2: new vivado version sourceme

# Run this in the directory of the board you'd like to upgrade
project=$(basename $(pwd))
source $1
make project

cp src/tcl/${project}_bd.tcl src/tcl/${project}_bd.tcl.bak

source $2
vivado -mode batch -source ../common/scripts/upgrade_version.tcl -tclargs */*.xpr src/tcl/${project}_bd.tcl
