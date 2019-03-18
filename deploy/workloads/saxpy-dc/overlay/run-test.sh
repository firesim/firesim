SIZES=(4096 32768 262144 2097152 16777216 134217728 1073741824)

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
