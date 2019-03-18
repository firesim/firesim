SIZES=(4096 32768 262144 2097152 16777216 134217728 1073741824)

for size in ${SIZES[@]}
do
    /root/saxpy.riscv -n $size
done

for size in ${SIZES[@]}
do
    /root/saxpy-pf.riscv -n $size
done
