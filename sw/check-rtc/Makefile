CC=riscv64-unknown-elf-gcc
CFLAGS=-mcmodel=medany -Wall -O2 -fno-common -fno-builtin-printf
LDFLAGS=-static -nostdlib -nostartfiles -lgcc

CC_LINUX=riscv64-unknown-linux-gnu-gcc
CFLAGS_LINUX=-Wall -O2
LDFLAGS_LINUX=-lrt

default: check-rtc-linux

baremetal_bins = $(addsuffix -bare, check-rtc check-tile-cmuxing read-temp)
linux_bins = $(addsuffix -linux, check-rtc)

%-linux: %-linux.c
	$(CC_LINUX) $(CFLAGS_LINUX) $(LDFLAGS_LINUX) $< -o $@

%-bare: %-bare.o crt.o syscalls.o
	$(CC) -T link.ld $(LDFLAGS) $^ -o $@

print-mcycle-linux: print-mcycle.c
	$(CC_LINUX) $(CFLAGS_LINUX) $(LDFLAGS_LINUX) $< -o $@

%.o: %.c util.h encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

%.o: %.S
	$(CC) $(CFLAGS) -c $< -o $@


baremetal: $(baremetal_bins)
linux: $(linux_bins)

clean:
	rm -f $(baremetal_bins)
	rm -f $(linux_bins)
	rm -f *.o

.PHONY: clean baremetal linux
