#!/bin/bash
set -e

SPIKE_INSTALL=$PWD/spike_local
mkdir -p $SPIKE_INSTALL

# Get the custom spike
if [ ! -d riscv-isa-sim ]; then
  git clone https://github.com/riscv/riscv-isa-sim.git
  pushd riscv-isa-sim
  git checkout 2dbcb01ca1c026b867cf673203646d213f6e6b5c
  popd

  pushd riscv-isa-sim
  git apply ../spike.patch
  popd
fi
  
# Spike is sensitive to toolchain changes, best to simply reconfigure every time
rm -rf riscv-isa-sim/build
mkdir riscv-isa-sim/build
pushd riscv-isa-sim/build
../configure --with-fesvr=$RISCV --prefix=$SPIKE_INSTALL
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

