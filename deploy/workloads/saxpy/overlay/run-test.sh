SIZES=(1000 10000 100000 1000000 10000000 100000000)

for size in ${SIZES[@]}
do
    taskset 0x1 /root/saxpy.riscv -n $size
done

for size in ${SIZES[@]}
do
    taskset 0x1 /root/saxpy.riscv -n $size -p
done
