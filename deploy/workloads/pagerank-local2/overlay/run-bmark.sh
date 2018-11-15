#!/bin/sh

DSTMAC=0400006D1200
STARTPAGE=5
BARRIER_SPAN=16

mknod /dev/remote-mem c 254 0

for size in 1024 2048 4096 #8192
do
    /root/pagerank-local.riscv -d $DSTMAC -s $STARTPAGE -b $BARRIER_SPAN \
        -n $size -p 2 -i $1 /root/pagerank-data-${size}.bin
done

poweroff
