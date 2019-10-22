#!/bin/bash

mknod /dev/dram-cache-exttab c 250 0
mknod /dev/dram-cache-mem    c 250 1

/root/dram-cache-test.riscv -d 300 -s 8
ping -c 1 172.16.0.3
