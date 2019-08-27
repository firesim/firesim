#!/bin/bash

mknod /dev/dram-cache-exttab c 254 0
mknod /dev/dram-cache-mem    c 254 1

taskset 0x1 /root/server.riscv -i 1 &
sleep 0.1
taskset 0x2 /root/client.riscv
