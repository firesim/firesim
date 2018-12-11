#!/usr/bin/env bash

RUNBIN=../target-design/firechip/tests/blkdev.riscv
#RUNBIN=../riscv-tools-install/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-add 

# generate a fresh test.stuff disk, all zeroed
dd if=/dev/zero bs=1M count=128 of=test.disk

./generated-src/f1/FireSimRocketChipConfig/FireSim +mm_readLatency=10 +mm_writeLatency=10 +mm_readMaxReqs=4 +mm_writeMaxReqs=4 +blkdev0=test.disk $RUNBIN
