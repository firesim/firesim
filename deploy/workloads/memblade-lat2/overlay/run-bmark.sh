#!/bin/sh

DSTMAC=0400006D1200

mknod /dev/remote-mem c 254 0

/root/memblade-lat.riscv -m 2 -n 100 -s 6 -d $DSTMAC -p 2 -b 3 
/root/memblade-lat.riscv -m 2 -n 100 -s 6 -d $DSTMAC -p 2 -b 4 -q
/root/memblade-lat.riscv -m 2 -n 100 -s 6 -d $DSTMAC -p 2 -b 5 -q

poweroff
