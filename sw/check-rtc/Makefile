CC=riscv64-unknown-elf-gcc
CFLAGS=-mcmodel=medany -Wall -O2 -fno-common -fno-builtin-printf
LDFLAGS=-static -nostdlib -nostartfiles -lgcc

CC_LINUX=riscv64-unknown-linux-gnu-gcc
CFLAGS_LINUX=-Wall -O2
LDFLAGS_LINUX=-lrt

check-rtc-linux: check-rtc-linux.c
	$(CC_LINUX) $(CFLAGS_LINUX) $(LDFLAGS_LINUX) $< -o $@

check-rtc: check-rtc.o crt.o syscalls.o
	$(CC) -T link.ld $(LDFLAGS) $^ -o $@

print-mcycle-linux: print-mcycle.c
	$(CC_LINUX) $(CFLAGS_LINUX) $(LDFLAGS_LINUX) $< -o $@

%.o: %.c util.h encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

%.o: %.S
	$(CC) $(CFLAGS) -c $< -o $@

