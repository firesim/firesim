#!/bin/bash

# This script is called by FireSim's bitbuilder to create a xclbin

# exit script if any command fails
set -e
set -o pipefail

usage() {
    echo "usage: ${0} [OPTIONS]"
    echo ""
    echo "Options"
    echo "   --build_dir : Build directory to run make command from"
    echo "   --device    : Vitis FPGA platform string"
    echo "   --help      : Display this message"
    exit "$1"
}

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
        * )
            echo "invalid option $1"
            usage 1 ;;
    esac
    shift
done

if [ -z "$BUILD_DIR" ] ; then
    echo "no build directory specified"
    usage 1
fi

if [ -z "$DEVICE" ] ; then
    echo "no device specified"
    usage 1
fi

# run build
make -C $BUILD_DIR DEVICE=$DEVICE bitstream
