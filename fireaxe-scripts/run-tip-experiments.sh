#!/bin/bash

set -e

FIREAXE_SCRIPT_DIR=$(pwd)
FIRESIM_BASEDIR=$FIREAXE_SCRIPT_DIR/../
FIRESIM_SIMULATION_DIR=$FIRESIM_BASEDIR/deploy/sim-dir
INTERMEDIATE_DIR=$FIREAXE_SCRIPT_DIR/tip-intermediate
TIP_OUTPUTDIR=$FIREAXE_SCRIPT_DIR/intermediate/tip-outputs

EMBENCH_DIR=$FIRESIM_BASEDIR/target-design/chipyard/software/embench

function generate_directory() {
    if [ -d $1 ]; then
        rm -rf $1
    fi
    mkdir -p $1
}

function build_embench() {
    cd $EMBENCH_DIR
    if [ -d build ]; then
        rm -rf build
    fi
    ./build.sh

    echo "Copying binary to deploy/workloads/embench-bare"
    cp build/* $FIRESIM_BASEDIR/deploy/workloads/embench-bare/
    cd $FIREAXE_SCRIPT_DIR
}

function build_embench_x86() {
    cd $EMBENCH_DIR
    if [ -d build-x86 ]; then
        rm -rf build-x86
    fi
    ./build-x86.sh
}

function firesim_runworkload() {
    cd $FIREAXE_SCRIPT_DIR
    [ -e $2_* ] && rm $2_*
    ./generate-baremetal-workload-spec.py \
        --parallel-sims $1 \
        --binary-dir $2 \
        --num-fpgas-per-partition $3

    WORKLOAD_SPEC_JSON=$2-firesim-workload-specs.json
    cd $INTERMEDIATE_DIR
    WORKLOAD_LIST=$(jq -r '.[]' $WORKLOAD_SPEC_JSON)

    cd $FIREAXE_SCRIPT_DIR

    for WORKLOAD in $WORKLOAD_LIST
    do
        OUT_CONFIG_FILE=${WORKLOAD}_config_runtime.yaml
        echo $OUT_CONFIG_FILE
        ./generate-config-runtime.py \
            --sim-dir $FIRESIM_SIMULATION_DIR/$8-$WORKLOAD \
            --topology $4 \
            --tip-enable true \
            --tip-core-width $5 \
            --tip-rob-depth $6 \
            --default-hw-config $7 \
            --partition-seed 0 \
            --workload-name $WORKLOAD.json \
            --out-config-file $OUT_CONFIG_FILE
        mv $OUT_CONFIG_FILE $INTERMEDIATE_DIR
        firesim infrasetup  -c $INTERMEDIATE_DIR/$OUT_CONFIG_FILE
        firesim runworkload -c $INTERMEDIATE_DIR/$OUT_CONFIG_FILE
        firesim kill
    done
}

function process_run_for_config() {
    ./tip-process-all-runs.py \
        --fpgas-per-partition $1 \
        --firesim-sim-dir $2 \
        --cfg-pfx $3 \
        --benchmark-name $4 \
        --output-dir $5 \
        --intermediate-dir $6
}

function  run_golden_cove_40() {
    BENCHMARK_NAME="embench-bare"
    CONFIG_PFX=gc-40
    SIMS_PER_RUN=1
    FPGAS_PER_RUN=2
    OUTPUT_DIR=$INTERMEDIATE_DIR/$CONFIG_PFX

# generate_directory $OUTPUT_DIR

    firesim_runworkload \
        $SIMS_PER_RUN \
        $BENCHMARK_NAME \
        $FPGAS_PER_RUN \
        fireaxe_xilinx_u250_golden_cove_40_config \
        6 216 \
        xilinx_u250_firesim_rocket_split_soc \
        $CONFIG_PFX
    process_run_for_config $FPGAS_PER_RUN $FIRESIM_SIMULATION_DIR $CONFIG_PFX $BENCHMARK_NAME $OUTPUT_DIR $INTERMEDIATE_DIR
    ./tip-output-csv.py --tip-results-dir $CONFIG_PFX > $INTERMEDIATE_DIR/TIP-OUTPUT-PIPELINE-$CONFIG_PFX-$BENCHMARK_NAME.csv
}


function run_large_boom() {
    BENCHMARK_NAME="embench-bare"
    CONFIG_PFX=large
    SIMS_PER_RUN=1
    FPGAS_PER_RUN=1
    OUTPUT_DIR=$INTERMEDIATE_DIR/$CONFIG_PFX

# generate_directory $OUTPUT_DIR
    firesim_runworkload \
        $SIMS_PER_RUN \
        $BENCHMARK_NAME \
        $FPGAS_PER_RUN \
        no_net_config \
        3 96 \
        xilinx_u250_firesim_large_boom \
        $CONFIG_PFX
    process_run_for_config $FPGAS_PER_RUN $FIRESIM_SIMULATION_DIR $CONFIG_PFX $BENCHMARK_NAME $OUTPUT_DIR $INTERMEDIATE_DIR
    ./tip-output-csv.py --tip-results-dir $CONFIG_PFX > $INTERMEDIATE_DIR/TIP-OUTPUT-PIPELINE-$CONFIG_PFX-$BENCHMARK_NAME.csv
}

function run_mega_boom() {
    BENCHMARK_NAME="embench-bare"
    CONFIG_PFX=megaboom
    SIMS_PER_RUN=1
    FPGAS_PER_RUN=1
    OUTPUT_DIR=$INTERMEDIATE_DIR/$CONFIG_PFX

    generate_directory $OUTPUT_DIR
    firesim_runworkload \
        $SIMS_PER_RUN \
        $BENCHMARK_NAME \
        $FPGAS_PER_RUN \
        no_net_config \
        4 128 \
        xilinx_u250_firesim_megaboom_config \
        $CONFIG_PFX
    process_run_for_config $FPGAS_PER_RUN $FIRESIM_SIMULATION_DIR $CONFIG_PFX $BENCHMARK_NAME $OUTPUT_DIR $INTERMEDIATE_DIR
    ./tip-output-csv.py --tip-results-dir $CONFIG_PFX > $INTERMEDIATE_DIR/TIP-OUTPUT-PIPELINE-$CONFIG_PFX-$BENCHMARK_NAME.csv
}

function run_x86_xeon() {
    cd $FIREAXE_SCRIPT_DIR
    echo "Running embench for xeons"
    ./tip-x86-test.py --binary-dir $EMBENCH_DIR/build-x86 > $INTERMEDIATE_DIR/TIP-IPC-XEON.csv
}

# build_embench
# run_golden_cove_40
# run_large_boom
# run_mega_boom



build_embench_x86
run_x86_xeon
