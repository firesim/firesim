#!/bin/sh

DSTMAC=0300006D1200

mknod /dev/remote-mem c 250 0

/root/memblade-bmark.riscv -n 10000 -s 4 -w 16 -r 16 -d $DSTMAC

poweroff
