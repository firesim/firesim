#!/bin/bash

set -ex

pushd ../target-design/chipyard/tests
make
popd

rm -rf generated-src/f1/*/*.out
rm -rf generated-src/f1/*/TRACEFILE*

make \
    DESIGN=FireSim \
    TARGET_CONFIG=VitisFireSimRocketConfig \
    PLATFORM_CONFIG=BaseVitisConfig \
    SIM_BINARY=$PWD/../target-design/chipyard/tests/hello.riscv \
    run-vcs

tail -n 10 generated-src/f1/*/*.out
tail -n 10 generated-src/f1/*/TRACEFILE*
