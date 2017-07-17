#!/bin/bash

export MAKEFLAGS=-j16

cp buildroot-config buildroot/.config
cd buildroot
make -j16
cd ..

cp linux-config riscv-linux/.config
cd riscv-linux
make -j16 ARCH=riscv vmlinux
cd ..

cd riscv-pk
mkdir build
cd build
../configure --host=riscv64-unknown-elf --with-payload=../../riscv-linux/vmlinux
make -j16
cp bbl ../../bbl-vmlinux
