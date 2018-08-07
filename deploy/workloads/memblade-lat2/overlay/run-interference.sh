#!/bin/sh

DSTMAC=0400006D1200

mknod /dev/remote-mem c 254 0

/root/memblade-bmark.riscv -n 10000 -s 1250 -w 15 -r 16 -p 2 -d $DSTMAC -b 3 -i -q 1
/root/memblade-bmark.riscv -n 10000 -s 1250 -w 15 -r 16 -p 2 -d $DSTMAC -b 4 -i -q 1
/root/memblade-bmark.riscv -n 10000 -s 6    -w 15 -r 16 -p 2 -d $DSTMAC -b 5 -i -q 1

poweroff
