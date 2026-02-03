#!/bin/bash

# This script is called by FireSim's bitbuilder to create an AFI for AWS F2.
# F2 uses a Python-based build script instead of F1's shell script.
#
# Key differences from F1:
# - F2's clk_main_a0 is fixed at 250MHz (not configurable like F1)
# - F2 uses clock recipes (A0/A1/A2) instead of direct frequency specification
# - F2 build script is Python-based (aws_build_dcp_from_cl.py)
# - F2 uses AMD Virtex UltraScale+ HBM VU47P FPGAs (vs Xilinx VU9P on F1)

# exit script if any command fails
set -e
set -o pipefail

usage() {
    echo "usage: ${0} [OPTIONS]"
    echo ""
    echo "Options"
    echo "   --cl_dir    : Custom logic directory to build AWS F2 bitstream from"
    echo "   --frequency : Frequency in MHz (note: F2 clk_main_a0 is fixed at 250MHz)"
    echo "   --strategy  : Build strategy (maps to placement/routing directives)"
    echo "   --help      : Display this message"
    echo ""
    echo "Note: F2's main clock (clk_main_a0) is fixed at 250MHz."
    echo "      The --frequency parameter is accepted for compatibility but"
    echo "      values other than 250 will generate a warning."
    exit "$1"
}

CL_DIR=""
FREQUENCY=""
STRATEGY=""

# getopts does not support long options, and is inflexible
while [ "$1" != "" ];
do
    case $1 in
        --help)
            usage 0 ;;
        --cl_dir )
            shift
            CL_DIR=$1 ;;
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

if [ -z "$CL_DIR" ] ; then
    echo "ERROR: no cl directory specified"
    usage 1
fi

if [ -z "$FREQUENCY" ] ; then
    echo "ERROR: No --frequency specified"
    usage 1
fi

if [ -z "$STRATEGY" ] ; then
    echo "ERROR: No --strategy specified"
    usage 1
fi

# Warn if frequency doesn't match F2's fixed 250MHz main clock
if [ "$FREQUENCY" != "250" ]; then
    echo "WARNING: F2's clk_main_a0 is fixed at 250MHz."
    echo "         Requested frequency ${FREQUENCY}MHz will be ignored for main clock."
    echo "         Consider using AWS_CLK_GEN IP for custom frequencies."
fi

# Map strategy to F2 placement/routing directives
# F2 uses different directive names than F1
case $STRATEGY in
    "BASIC")
        PLACE_DIRECTIVE="SSI_SpreadLogic_high"
        ROUTE_DIRECTIVE="Explore"
        ;;
    "EXPLORE")
        PLACE_DIRECTIVE="SSI_SpreadLogic_high"
        ROUTE_DIRECTIVE="AggressiveExplore"
        ;;
    "TIMING"|"DEFAULT")
        PLACE_DIRECTIVE="SSI_SpreadLogic_high"
        ROUTE_DIRECTIVE="AggressiveExplore"
        ;;
    "CONGESTION")
        PLACE_DIRECTIVE="AltSpreadLogic_high"
        ROUTE_DIRECTIVE="AlternateCLBRouting"
        ;;
    *)
        # Use the strategy as-is for routing directive
        PLACE_DIRECTIVE="SSI_SpreadLogic_high"
        ROUTE_DIRECTIVE="$STRATEGY"
        ;;
esac

AWS_FPGA_DIR=$CL_DIR/../../../..

# setup hdk for F2
cd $AWS_FPGA_DIR
source hdk_setup.sh

export CL_DIR=$CL_DIR

# Get the CL name from the directory structure
CL_NAME=$(basename $CL_DIR)

# run build using F2's Python-based build script
cd $CL_DIR/build/scripts

echo "================================================================"
echo "Building F2 AFI for $CL_NAME"
echo "  Placement directive: $PLACE_DIRECTIVE"
echo "  Routing directive: $ROUTE_DIRECTIVE"
echo "  Clock recipe: A1 (clk_main_a0=250MHz, clk_extra_a1=125MHz)"
echo "================================================================"

# F2 uses aws_build_dcp_from_cl.py instead of aws_build_dcp_from_cl.sh
python3 aws_build_dcp_from_cl.py \
    --cl=$CL_NAME \
    --mode=small_shell \
    --flow=BuildAll \
    --clock_recipe_a=A1 \
    --clock_recipe_b=B2 \
    --clock_recipe_c=C0 \
    --clock_recipe_hbm=H2 \
    --place=$PLACE_DIRECTIVE \
    --route=$ROUTE_DIRECTIVE
