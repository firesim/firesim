#!/usr/bin/env bash

sudo yum -y install glib gtk2-devel gtk3-devel

cd qemu
git submodule update --init --recursive
./configure --target-list=riscv64-softmmu --prefix=$RISCV
make -j16
make install
