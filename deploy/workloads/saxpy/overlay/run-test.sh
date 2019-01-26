SIZES=(1024 4096 16384 65536 524288 8388608 268435456)

for size in ${SIZES[@]}
do
    /root/saxpy.riscv -n $size
done

for size in ${SIZES[@]}
do
    /root/saxpy-pf.riscv -n $size
done
