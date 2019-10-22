#!/bin/sh

DSTMAC=0300006D1200
STARTPAGE=4

mknod /dev/remote-mem c 250 0

for size in 512 1024 2048 #4096 8192
do
    /root/load-memblade.riscv -d $DSTMAC -s $STARTPAGE /root/pagerank-data-${size}.bin
    /root/pagerank-remote.riscv -d $DSTMAC -s $STARTPAGE -n $size
done

poweroff
