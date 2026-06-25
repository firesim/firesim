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
    echo "   --pr_partition_module_name : Original module name(s) used in the main_pr.tcl build (for partition def lookup in main_pr_rm.tcl)."
    echo "                               Comma-separated if multiple. Defaults to --pr_module_name if omitted (backward compatible)."
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
PR_PARTITION_MODULE_NAME=""

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
        --pr_partition_module_name )
            shift
            PR_PARTITION_MODULE_NAME=$1 ;;
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

PR_METADATA_SCRIPT="$CL_DIR/scripts/pr_metadata.py"

# run build
cd $CL_DIR
if [ "$ENABLE_PR" = "true" ] ; then
    # Use main_pr_rm.tcl if pr_project_path is specified, otherwise use main_pr.tcl
    if [ -n "$PR_PROJECT_PATH" ] ; then
        # Validate sources against original build's metadata before launching Vivado
        python3 "$PR_METADATA_SCRIPT" validate \
            --root_dir "$CL_DIR" \
            --project_path "$PR_PROJECT_PATH" \
            --pr_module_names "$PR_MODULE_NAME" \
            --pr_partition_paths "$PR_PARTITION_PATH" \
            --frequency "$FREQUENCY" \
            || echo "WARNING: PR metadata validation returned warnings (see above). Continuing build..."

        vivado -mode batch -source $CL_DIR/scripts/main_pr_rm.tcl -tclargs $FREQUENCY $STRATEGY $BOARD "$PR_MODULE_NAME" "$PR_PARTITION_PATH" "$PR_PROJECT_PATH" "${PR_PARTITION_MODULE_NAME}"
    else
        vivado -mode batch -source $CL_DIR/scripts/main_pr.tcl -tclargs $FREQUENCY $STRATEGY $BOARD "$PR_MODULE_NAME" "${PR_PARTITION_PATH:-}"

        # Generate PR metadata after successful build
        DISCOVERED_PATHS_FILE="$CL_DIR/vivado_proj/discovered_pr_paths.txt"
        VIVADO_INFO_FILE="$CL_DIR/vivado_proj/vivado_build_info.txt"

        # Read Vivado-specific info written by main_pr.tcl
        VIVADO_VERSION=""
        PART=""
        BOARD_PART_VAL=""
        ACTUAL_FREQ=""
        if [ -f "$VIVADO_INFO_FILE" ] ; then
            VIVADO_VERSION=$(grep '^vivado_version=' "$VIVADO_INFO_FILE" | cut -d= -f2)
            PART=$(grep '^part=' "$VIVADO_INFO_FILE" | cut -d= -f2)
            BOARD_PART_VAL=$(grep '^board_part=' "$VIVADO_INFO_FILE" | cut -d= -f2)
            ACTUAL_FREQ=$(grep '^actual_frequency_mhz=' "$VIVADO_INFO_FILE" | cut -d= -f2)
        fi

        # Use actual (post-adjustment) frequency if available, otherwise requested
        METADATA_FREQ="${ACTUAL_FREQ:-$FREQUENCY}"

        GEN_CMD=(python3 "$PR_METADATA_SCRIPT" generate
            --root_dir "$CL_DIR"
            --frequency "$METADATA_FREQ"
            --strategy "$STRATEGY"
            --pr_module_names "$PR_MODULE_NAME"
            --vivado_version "$VIVADO_VERSION"
            --part "$PART"
            --board_part "$BOARD_PART_VAL"
        )

        if [ -n "$PR_PARTITION_PATH" ] ; then
            GEN_CMD+=(--pr_partition_paths "$PR_PARTITION_PATH")
        fi
        if [ -f "$DISCOVERED_PATHS_FILE" ] ; then
            GEN_CMD+=(--discovered_paths_file "$DISCOVERED_PATHS_FILE")
        fi

        "${GEN_CMD[@]}" || echo "WARNING: PR metadata generation failed (see above). Bitstream was built successfully."
    fi
else
    vivado -mode batch -source $CL_DIR/scripts/main.tcl -tclargs $FREQUENCY $STRATEGY $BOARD
fi
