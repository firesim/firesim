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
    echo "   --board     : FPGA board {mimicturbo_gt}."
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

# The manager runs this script over ssh in a non-interactive login shell on the build host. An
# optional build-env.sh in this platform directory (rsynced to the build host with the rest of the
# platform directory; git-ignored) is sourced first, for anything that shell lacks: a Vivado
# settings64.sh, XILINXD_LICENSE_FILE, etc. The manager copies this script into the cl_* build
# directory one level below the platform directory, so look in both places.
for envscript in "$(dirname "$0")/build-env.sh" "$(dirname "$0")/../build-env.sh"; do
    if [ -f "$envscript" ]; then
        echo "sourcing $envscript"
        source "$envscript"
        break
    fi
done
# If vivado is still not on PATH, source a settings64.sh ourselves: VIVADO_SETTINGS64 if set,
# otherwise the newest Vivado found in the usual install roots.
if ! command -v vivado >/dev/null 2>&1; then
    if [ -n "$VIVADO_SETTINGS64" ] && [ -f "$VIVADO_SETTINGS64" ]; then
        source "$VIVADO_SETTINGS64"
    else
        for s in $(ls -d /tools/xilinx/Vivado/*/settings64.sh /tools/Xilinx/Vivado/*/settings64.sh /opt/Xilinx/Vivado/*/settings64.sh 2>/dev/null | sort -V); do
            VIVADO_SETTINGS64_FOUND=$s
        done
        if [ -n "$VIVADO_SETTINGS64_FOUND" ]; then
            echo "vivado not on PATH, sourcing $VIVADO_SETTINGS64_FOUND"
            source "$VIVADO_SETTINGS64_FOUND"
        fi
    fi
fi
if ! command -v vivado >/dev/null 2>&1; then
    echo "vivado not found on PATH and no settings64.sh located; set VIVADO_SETTINGS64 or source Vivado in the build host's login shell"
    exit 1
fi

# run build
cd $CL_DIR
# -stack 2000: bump per-thread Tcl interpreter stack from the default ~256 KB
# to 2 MB. Required to keep synth_design from hitting `[Synth 8-7098] Stack
# limit reached` during LSU/FSM inference on large BOOM designs. Propagates
# to vivado processes spawned by launch_runs.
vivado -stack 2000 -mode batch -source $CL_DIR/scripts/main.tcl -tclargs $FREQUENCY $STRATEGY $BOARD
