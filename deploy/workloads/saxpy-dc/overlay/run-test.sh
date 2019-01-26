SIZES=(1024 4096 16384 65536 524288 8388608 268435456)

mknod /dev/dram-cache-exttab c 254 0
mknod /dev/dram-cache-mem    c 254 1

for size in ${SIZES[@]}
do
    /root/saxpy-dc.riscv -n $size -d 300 -s 4
done

for size in ${SIZES[@]}
do
    /root/saxpy-dc-pf.riscv -n $size -d 300 -s 4
done
