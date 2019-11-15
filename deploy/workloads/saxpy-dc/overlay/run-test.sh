SIZES=(1000 10000 100000 1000000 10000000 100000000)

mknod /dev/dram-cache-exttab c 250 0
mknod /dev/dram-cache-mem    c 250 1

CPUMASK=$(/root/boom-cpumask.riscv)

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-dc.riscv -n $size -d 300 -s 8
done

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-dc.riscv -n $size -d 300 -s 8 -p
done
