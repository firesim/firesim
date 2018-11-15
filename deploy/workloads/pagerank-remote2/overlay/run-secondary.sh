#!/bin/sh

DSTMAC=0400006D1200
STARTPAGE=4

mknod /dev/remote-mem c 254 0

for size in 1024 2048 4096 #8192
do
    /root/pagerank-remote.riscv -d $DSTMAC -s $STARTPAGE -n $size -p 2 -i 1
done

poweroff
