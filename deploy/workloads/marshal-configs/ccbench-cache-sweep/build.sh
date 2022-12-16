#!/bin/bash

pushd ccbench/caches
make ARCH=riscv
popd
cp -a ccbench/caches/caches overlay/ccbench-cache-sweep/.
