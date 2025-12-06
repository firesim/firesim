#!/bin/bash

# This script is called by FireSim's bitbuilder to create a bit file

# exit script if any command fails
set -e
set -o pipefail

usage() {
    echo "usage: ${0} [OPTIONS]"
    echo ""
    echo "Options"
    echo "   --cl_dir          : Custom logic directory to build Vivado bitstream from"
    echo "   --frequency       : Frequency in MHz of the desired FPGA host clock."
    echo "   --strategy        : A string to a precanned set of build directives.
                                  See aws-fpga documentation for more info/.
                                  For this platform TIMING and AREA supported."
    echo "   --board           : FPGA board {au200,au250,au280}."
    echo "   --enable_pr       : Enable Partial Reconfiguration (true/false)"
    echo "   --pr_module_name  : Name of the PR (Partial Reconfiguration) module (required if --enable_pr is true)"
    echo "   --pr_partition_path : Hierarchical path to the PR partition in the design (required if --enable_pr is true)"
    echo "   --help            : Display this message"
    exit "$1"
}

CL_DIR=""
FREQUENCY=""
STRATEGY=""
BOARD=""
ENABLE_PR="false"
PR_MODULE_NAME=""
PR_PARTITION_PATH=""

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
        --enable_pr )
            shift
            ENABLE_PR=$1 ;;
        --pr_module_name )
            shift
            PR_MODULE_NAME=$1 ;;
        --pr_partition_path )
            shift
            PR_PARTITION_PATH=$1 ;;
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

# Check PR arguments only if PR is enabled
if [ "$ENABLE_PR" = "true" ] ; then
    if [ -z "$PR_MODULE_NAME" ] ; then
        echo "No --pr_module_name specified (required when --enable_pr is true)"
        usage 1
    fi

    if [ -z "$PR_PARTITION_PATH" ] ; then
        echo "No --pr_partition_path specified (required when --enable_pr is true)"
        usage 1
    fi
fi

# run build
cd $CL_DIR
if [ "$ENABLE_PR" = "true" ] ; then
    vivado -mode batch -source $CL_DIR/scripts/main_pr.tcl -tclargs $FREQUENCY $STRATEGY $BOARD $PR_MODULE_NAME $PR_PARTITION_PATH
else
    vivado -mode batch -source $CL_DIR/scripts/main.tcl -tclargs $FREQUENCY $STRATEGY $BOARD
fi
