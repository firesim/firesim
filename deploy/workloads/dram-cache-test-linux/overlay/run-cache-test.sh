#!/bin/bash

mknod /dev/dram-cache-exttab c 249 0
mknod /dev/dram-cache-mem    c 249 1

/root/dram-cache-test.riscv -d 300 -s 8
ping -c 3 172.16.0.3
