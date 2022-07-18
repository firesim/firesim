#!/bin/bash

# This script is called by FireSim's bitbuilder to create a xclbin

# exit script if any command fails
set -e
set -o pipefail

usage() {
    echo "usage: ${0} --build_dir <dir> --device <platform_string> --frequency <[1.0, 300.0]> --strategy <TIMING|AREA|...>"
    echo ""
    echo "Options"
    echo "   --build_dir : Build directory to run make command from"
    echo "   --device    : The vitis FPGA platform name"
    echo "                 e.g., xilinx_u250_gen3x16_xdma_3_1_202020_1"
    echo "   --frequency : Frequency in MHz of the desired FPGA host clock."
    echo "   --strategy  : A string calling out one of the precanned set of directives."
    echo "                 See docs.fires.im for more information."
    echo "   --help      : Display this message"
    exit "$1"
}

FREQUENCY=""
STRATEGY=""
BUILD_DIR=""
DEVICE=""

# getopts does not support long options, and is inflexible
while [ "$1" != "" ];
do
    case $1 in
        --help)
            usage 1 ;;
        --build_dir )
            shift
            BUILD_DIR=$1 ;;
        --device )
            shift
            DEVICE=$1 ;;
        --strategy )
            shift
            STRATEGY=$1 ;;
        --frequency )
            shift
            FREQUENCY=$1 ;;
        * )
            echo "invalid option $1"
            usage 1 ;;
    esac
    shift
done

if [ -z "$BUILD_DIR" ] ; then
    echo "No build directory specified"
    usage 1
fi

if [ -z "$DEVICE" ] ; then
    echo "No device specified"
    usage 1
fi

if [ -z "$FREQUENCY" ] ; then
    echo "No frequency specified"
    usage 1
fi

if [ -z "$STRATEGY" ] ; then
    echo "No strategy specified"
    usage 1
fi

# run build
make -C $BUILD_DIR DEVICE=$DEVICE FREQUENCY=$FREQUENCY STRATEGY=$STRATEGY bitstream
