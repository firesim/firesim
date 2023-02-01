#!/bin/bash

pushd ccbench/caches
make ARCH=riscv
popd
mkdir -p overlay/ccbench-cache-sweep/ccbench/caches/
cp -a ccbench/caches/caches overlay/ccbench-cache-sweep/ccbench/caches/
