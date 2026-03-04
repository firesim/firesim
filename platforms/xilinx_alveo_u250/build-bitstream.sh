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
    echo "   --pr_module_name  : Name(s) of the PR (Partial Reconfiguration) module(s), comma-separated if multiple (required if --enable_pr is true)"
    echo "   --pr_partition_path : Hierarchical path(s) to the PR partition(s), comma-separated if multiple (optional for main_pr; required for main_pr_rm)"
    echo "   --pr_project_path : Path to a previous .xpr project file (optional, if specified uses main_pr_rm.tcl instead of main_pr.tcl)"
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
PR_PROJECT_PATH=""

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
        --pr_project_path )
            shift
            PR_PROJECT_PATH=$1 ;;
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

    # pr_partition_path is optional for main_pr (discovery mode); validate counts only when provided
    if [ -n "$PR_PARTITION_PATH" ] ; then
        module_count=$(echo "$PR_MODULE_NAME" | tr ',' '\n' | wc -l)
        path_count=$(echo "$PR_PARTITION_PATH" | tr ',' '\n' | wc -l)
        if [ "$module_count" -ne "$path_count" ] ; then
            echo "Error: pr_module_name and pr_partition_path must have the same number of items"
            echo "  Found $module_count module name(s) and $path_count partition path(s)"
            usage 1
        fi
    fi
fi

# run build
cd $CL_DIR
if [ "$ENABLE_PR" = "true" ] ; then
    # Use main_pr_rm.tcl if pr_project_path is specified, otherwise use main_pr.tcl
    if [ -n "$PR_PROJECT_PATH" ] ; then
        vivado -mode batch -source $CL_DIR/scripts/main_pr_rm.tcl -tclargs $FREQUENCY $STRATEGY $BOARD "$PR_MODULE_NAME" "$PR_PARTITION_PATH" "$PR_PROJECT_PATH"
    else
        vivado -mode batch -source $CL_DIR/scripts/main_pr.tcl -tclargs $FREQUENCY $STRATEGY $BOARD "$PR_MODULE_NAME" "${PR_PARTITION_PATH:-}"
    fi
else
    vivado -mode batch -source $CL_DIR/scripts/main.tcl -tclargs $FREQUENCY $STRATEGY $BOARD
fi
