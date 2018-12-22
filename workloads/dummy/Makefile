CC=riscv64-unknown-elf-gcc
CFLAGS=-mcmodel=medany -Wall -O2 -fno-common -fno-builtin-printf
LDFLAGS=-static -nostdlib -nostartfiles -lgcc

dummy: dummy.o
	$(CC) -T link.ld $(LDFLAGS) $^ -o $@

dummy.rootfs:
	dd if=/dev/zero of=dummy.rootfs bs=1M count=1
	mke2fs -F dummy.rootfs

%.o: %.c util.h encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

%.o: %.S
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -f *.o
	rm -f dummy
	rm -f dummy.rootfs
