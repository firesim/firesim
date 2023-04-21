#!/bin/bash

set -ex

pushd ../target-design/chipyard/tests
make
popd

rm -rf generated-src/vitis/*/*.out
rm -rf generated-src/vitis/*/TRACEFILE*

make \
    PLATFORM=vitis \
    DESIGN=FireSim \
    TARGET_CONFIG=VitisFireSimRocketConfig \
    PLATFORM_CONFIG=BaseVitisConfig \
    SIM_BINARY=$PWD/../target-design/chipyard/tests/hello.riscv \
    run-vcs

tail -n 10 generated-src/vitis/*/*.out
tail -n 10 generated-src/vitis/*/TRACEFILE*
