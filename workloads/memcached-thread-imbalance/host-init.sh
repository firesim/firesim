#!/bin/bash
git submodule update --init mutilate-loadgen-riscv-release
cd mutilate-loadgen-riscv-release/
./build.sh
