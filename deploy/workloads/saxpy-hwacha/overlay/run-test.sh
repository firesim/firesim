#SIZES=(1000 10000 100000 1000000 10000000 100000000)
#SIZES=(1000 10000)
SIZES=(1000000)

CPUMASK=$(/root/boom-cpumask.riscv)

for size in ${SIZES[@]}
do
    taskset $CPUMASK /root/saxpy-hwacha.riscv -n $size
    taskset $CPUMASK /root/saxpy-hwacha.riscv -n $size -f 1
done
