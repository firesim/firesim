#!/bin/bash
set -e

SPIKE_INSTALL=$PWD/spike_local
mkdir -p $SPIKE_INSTALL

# Build test program (hello world)
make hello

# Get the custom spike
if [ ! -d riscv-isa-sim ]; then
  git clone https://github.com/riscv/riscv-isa-sim.git
  pushd riscv-isa-sim
  git checkout 2dbcb01ca1c026b867cf673203646d213f6e6b5c
  popd

  pushd riscv-isa-sim
  git apply ../spike.patch

  mkdir build
  pushd build
  ../configure --with-fesvr=$RISCV --prefix=$SPIKE_INSTALL
  popd
  popd
fi

pushd riscv-isa-sim/build
make -j16
make install
popd
