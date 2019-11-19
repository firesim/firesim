#!/bin/bash
set -e

SPIKE_INSTALL=$PWD/spike_local
mkdir -p $SPIKE_INSTALL

# Get a version of spike that includes the dummy_rocc accelerator
if [ ! -d riscv-isa-sim ]; then
  git clone https://github.com/riscv/riscv-isa-sim.git
  pushd riscv-isa-sim
  git checkout 2dbcb01ca1c026b867cf673203646d213f6e6b5c
  popd
fi

# Spike is touchy about its configuration, better to reconfigure every time
# (just in case)
rm -rf riscv-isa-sim/build
pushd riscv-isa-sim
mkdir build
pushd build
../configure --with-fesvr=$RISCV --prefix=$SPIKE_INSTALL
popd
popd

pushd riscv-isa-sim/build
make -j16
make install
popd

# Build the benchmark
make
