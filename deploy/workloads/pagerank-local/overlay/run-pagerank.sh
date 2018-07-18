#!/bin/bash

for size in 512 1024 2048 #4096 8192
do
    /root/pagerank-local.riscv -n $size /root/pagerank-data-${size}.bin
done

poweroff
