SIZES=(1000 10000 100000 1000000 10000000 100000000)

CPUMASK=$(/root/boom-cpumask.riscv)

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy.riscv -n $size
done

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy.riscv -n $size -p
done
