#!/bin/bash

mknod /dev/dram-cache-exttab c 254 0
mknod /dev/dram-cache-mem    c 254 1

/root/dram-cache-test.riscv -s 3
