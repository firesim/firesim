#!/bin/bash

set -ex

make \
    DESIGN=FireSim \
    TARGET_CONFIG=VitisFireSimRocketConfig \
    PLATFORM_CONFIG=BaseVitisConfig \
    SIM_BINARY=/scratch/abejgonza/fs-fix-dma/target-design/chipyard/tests/hello.riscv \
    run-vcs
