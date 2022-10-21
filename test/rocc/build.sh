#!/bin/bash

set -ex

SPIKE_INSTALL=$PWD/spike_local
mkdir -p $SPIKE_INSTALL

# Get a version of spike that includes the dummy_rocc accelerator
if [ ! -d riscv-isa-sim ]; then
  git clone https://github.com/riscv/riscv-isa-sim.git
  pushd riscv-isa-sim
  git checkout 5a50590f25a932cc1f25fe78b9912e9661d37d30
  popd
fi

# Spike is touchy about its configuration, better to reconfigure every time
# (just in case)
rm -rf riscv-isa-sim/build
pushd riscv-isa-sim
mkdir build
pushd build
../configure --with-fesvr=$RISCV --prefix=$SPIKE_INSTALL --with-boost=no --with-boost-asio=no --with-boost-regex=no
popd
popd

pushd riscv-isa-sim/build
make -j16
make install
popd

# Build the benchmark
make
