#!/bin/bash

pushd ../../../../sw/network-benchmarks/fc-test
make
popd
ln -sf ../../../../sw/network-benchmarks/fc-test/fc-client.riscv fc-client.riscv
ln -sf ../../../../sw/network-benchmarks/fc-test/fc-server.riscv fc-server.riscv
