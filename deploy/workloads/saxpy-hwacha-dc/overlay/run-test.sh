#SIZES=(1000 10000 100000 1000000 10000000 100000000)
#SIZES=(100000000)
SIZES=(1000000)

mknod /dev/dram-cache-exttab c 250 0
mknod /dev/dram-cache-mem    c 250 1
mknod /dev/dram-cache-ctrl   c 250 2
mknod /dev/l2-cache-ctrl     c 251 0

CPUMASK=$(/root/boom-cpumask.riscv)
#TIMEOUT=8192
TIMEOUT=1048576

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-hwacha-dc.riscv -t $TIMEOUT -n $size -d 300 -s 8
    taskset $CPUMASK /root/saxpy-hwacha-dc.riscv -t $TIMEOUT -n $size -d 300 -s 8 -f 1
    taskset $CPUMASK /root/saxpy-hwacha-dc.riscv -t $TIMEOUT -n $size -d 300 -s 8 -f 2
done

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-hwacha-dc.riscv -t $TIMEOUT -n $size -d 300 -s 8 -p
    taskset $CPUMASK /root/saxpy-hwacha-dc.riscv -t $TIMEOUT -n $size -d 300 -s 8 -p -f 1
    taskset $CPUMASK /root/saxpy-hwacha-dc.riscv -t $TIMEOUT -n $size -d 300 -s 8 -p -f 2
done
