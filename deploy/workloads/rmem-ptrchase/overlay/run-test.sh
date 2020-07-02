#SIZES=(1000 10000 100000 1000000 10000000 100000000)
#SIZES=(10000000)
#SIZES=(1000000 2000000 5000000 7000000 10000000)

mknod /dev/l2-cache-ctrl     c 250 0
mknod /dev/remote-mem        c 249 0

#cp /root/ptrchase.img /tmp/ptrchase.img

#dd if=/root/ptrchase.img of=/dev/iceblk1 bs=4096
#dd if=/root/ptrchase.img of=/dev/remote-mem bs=4096 seek=4

#cat /proc/modules

COMMON_ARGS="-n 100 -s 4096 -a 1555558000 -f"
#COMMON_ARGS="-n 100 -s 131072 -a 1555558000"

#/root/rmem-ptrchase.riscv $COMMON_ARGS -p 0 -d /tmp/ptrchase.img
#/root/rmem-ptrchase.riscv $COMMON_ARGS -p 0 -d /root/ptrchase.img
#/root/rmem-ptrchase.riscv $COMMON_ARGS -p 0 -d /dev/iceblk1
/root/rmem-ptrchase.riscv $COMMON_ARGS -p 16 -d /dev/remote-mem
