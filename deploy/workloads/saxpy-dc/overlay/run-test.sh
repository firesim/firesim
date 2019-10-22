SIZES=(1000 10000 100000 1000000 10000000 100000000)

mknod /dev/dram-cache-exttab c 250 0
mknod /dev/dram-cache-mem    c 250 1

for size in ${SIZES[@]}
do
    taskset 0x1 /root/saxpy-dc.riscv -n $size -d 300 -s 4
done

for size in ${SIZES[@]}
do
    taskset 0x1 /root/saxpy-dc.riscv -n $size -d 300 -s 4 -p
done
