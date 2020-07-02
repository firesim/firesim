#SIZES=(1000 10000 100000 1000000 10000000 100000000)
SIZES=(10000000)
#SIZES=(1000000 2000000 5000000 7000000 10000000)

ip addr del fe80::212:6dff:fe00:2/64 dev eth0
ip addr

mknod /dev/l2-cache-ctrl     c 251 0

for size in ${SIZES[@]}
do
    #/root/saxpy-hwacha-pf.riscv -n $size -s 8 -f 0
    #/root/saxpy-hwacha-pf.riscv -n $size -s 8 -f 1
    /root/saxpy-hwacha-pf.riscv -n $size -s 8 -f 3
done
