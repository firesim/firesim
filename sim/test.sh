#!/usr/bin/env bash

set -ex

make \
    PLATFORM=xilinx_alveo_u250 \
    TARGET_PROJECT=firesim \
    DESIGN=FireSim \
    TARGET_CONFIG=FireSimRocketConfig \
    PLATFORM_CONFIG=BaseXilinxAlveoConfig \
    SIM_BINARY=/scratch/abejgonza/firesim-private/sw/firesim-software/test/bare/hello \
    clean
