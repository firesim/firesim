REPO_ROOT := $(abspath ../../../../..)
BARE_SRC := $(REPO_ROOT)/software/firemarshal/test/bare
CC := riscv64-unknown-elf-gcc
CFLAGS := -mcmodel=medany -march=rv64gc -mabi=lp64d -Wall -Wextra -O2 -fno-common -fno-builtin -I$(BARE_SRC)
LDFLAGS := -static -nostdlib -nostartfiles -lgcc

all: chip0.elf chip1.elf

crt.o: $(BARE_SRC)/crt.S $(BARE_SRC)/encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

syscalls.o: $(BARE_SRC)/syscalls.c $(BARE_SRC)/util.h $(BARE_SRC)/encoding.h
	$(CC) $(CFLAGS) -c $< -o $@

chip0.o: ctc-smoke.c
	$(CC) $(CFLAGS) -DCTC_CHIP_ID=0 -c $< -o $@

chip1.o: ctc-smoke.c
	$(CC) $(CFLAGS) -DCTC_CHIP_ID=1 -c $< -o $@

chip0.elf: chip0.o crt.o syscalls.o $(BARE_SRC)/link.ld
	$(CC) -T $(BARE_SRC)/link.ld $(LDFLAGS) chip0.o crt.o syscalls.o -o $@

chip1.elf: chip1.o crt.o syscalls.o $(BARE_SRC)/link.ld
	$(CC) -T $(BARE_SRC)/link.ld $(LDFLAGS) chip1.o crt.o syscalls.o -o $@

clean:
	rm -f chip0.o chip1.o crt.o syscalls.o chip0.elf chip1.elf

.PHONY: all clean
