#!/bin/sh

DSTMAC=0300006D1200

mknod /dev/remote-mem c 254 0

/root/memblade-lat.riscv -n 100 -s 4 -d $DSTMAC
/root/memblade-lat.riscv -n 100 -s 4 -d $DSTMAC -q

poweroff
