#!/bin/bash

set -ex

SPIKE_INSTALL=$PWD/spike_local
mkdir -p $SPIKE_INSTALL

# Get the custom spike
if [ ! -d riscv-isa-sim ]; then
  git clone https://github.com/riscv/riscv-isa-sim.git
  pushd riscv-isa-sim
  git checkout 5a50590f25a932cc1f25fe78b9912e9661d37d30
  popd

  pushd riscv-isa-sim
  git apply ../spike.patch
  popd
fi

# Spike is sensitive to toolchain changes, best to simply reconfigure every time
rm -rf riscv-isa-sim/build
mkdir riscv-isa-sim/build
pushd riscv-isa-sim/build
../configure --with-fesvr=$RISCV --prefix=$SPIKE_INSTALL --with-boost=no --with-boost-asio=no --with-boost-regex=no
popd

pushd riscv-isa-sim/build
make -j16
make install
popd

pushd ../bare
make
popd

if [ ! -f hello ]; then
  ln -s ../bare/hello .
fi

