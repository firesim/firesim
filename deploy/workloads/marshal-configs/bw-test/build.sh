#!/bin/bash

pushd ../../../../sw/network-benchmarks
python3 build-bw-test.py -n $1
popd
rm -rf *.riscv
cp ../../../../sw/network-benchmarks/testbuild/*.riscv .
