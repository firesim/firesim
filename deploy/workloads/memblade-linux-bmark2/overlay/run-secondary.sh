#!/bin/sh

DSTMAC=0400006D1200

mknod /dev/remote-mem c 254 0

/root/memblade-bmark.riscv -n 10000 -s 10004 -w 16 -r 16 -p 2 -d $DSTMAC -b 80016

poweroff
