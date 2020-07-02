#SIZES=(1000 10000 100000 1000000 10000000 100000000)
SIZES=(40000)

mknod /dev/dram-cache-exttab c 249 0
mknod /dev/dram-cache-mem    c 249 1

CPUMASK=$(/root/boom-cpumask.riscv)
echo "CPU mask: $CPUMASK"

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-dc.riscv -n $size -d 300 -s 8 -f 1
done

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-dc.riscv -n $size -d 300 -s 8 -p -f 2
done
