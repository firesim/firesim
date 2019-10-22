mknod /dev/dram-cache-exttab c 250 0
mknod /dev/dram-cache-mem    c 250 1
mknod /dev/dram-cache-ctrl   c 250 2
mknod /dev/inclusive-cache   c 251 0

sleep 0.1

/root/flush-test-master.riscv 400 172.16.0.3 4900
