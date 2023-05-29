#!/bin/bash

# This script is called by FireSim's bitbuilder to create a bit file

# exit script if any command fails
set -e
set -o pipefail

usage() {
    echo "usage: ${0} [OPTIONS]"
    echo ""
    echo "Options"
    echo "   --cl_dir    : Custom logic directory to build Vivado bitstream from"
    echo "   --frequency : Frequency in MHz of the desired FPGA host clock."
    echo "   --strategy  : A string to a precanned set of build directives.
                          See aws-fpga documentation for more info/.
                          For this platform TIMING and AREA supported."
    echo "   --board     : FPGA board {au250,au280}."
    echo "   --help      : Display this message"
    exit "$1"
}

CL_DIR=""
FREQUENCY=""
STRATEGY=""
BOARD=""

# getopts does not support long options, and is inflexible
while [ "$1" != "" ];
do
    case $1 in
        --help)
            usage 1 ;;
        --cl_dir )
            shift
            CL_DIR=$1 ;;
        --strategy )
            shift
            STRATEGY=$1 ;;
        --frequency )
            shift
            FREQUENCY=$1 ;;
        --board )
            shift
            BOARD=$1 ;;
        * )
            echo "invalid option $1"
            usage 1 ;;
    esac
    shift
done

if [ -z "$CL_DIR" ] ; then
    echo "no cl directory specified"
    usage 1
fi

if [ -z "$FREQUENCY" ] ; then
    echo "No --frequency specified"
    usage 1
fi

if [ -z "$STRATEGY" ] ; then
    echo "No --strategy specified"
    usage 1
fi

if [ -z "$BOARD" ] ; then
    echo "No --board specified"
    usage 1
fi

# run build
cd $CL_DIR/Nitefury-II/project
vivado -mode batch -source top.tcl -tclargs $FREQUENCY $STRATEGY

mkdir -p ../../vivado_proj
cp project/project.runs/impl_1/Top_wrapper.bit ../../vivado_proj/firesim.bit
cp ../mcs/out.mcs ../../vivado_proj/firesim.mcs
