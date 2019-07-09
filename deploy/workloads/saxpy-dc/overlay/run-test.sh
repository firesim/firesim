SIZES=(1000 10000 100000 1000000 10000000 100000000)

mknod /dev/dram-cache-exttab c 254 0
mknod /dev/dram-cache-mem    c 254 1

for size in ${SIZES[@]}
do
    /root/saxpy-dc.riscv -n $size -d 300 -s 4
done

for size in ${SIZES[@]}
do
    /root/saxpy-dc.riscv -n $size -d 300 -s 4 -p
done
