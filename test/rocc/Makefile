CC=riscv64-unknown-elf-gcc
OBJDUMP=riscv64-unknown-elf-objdump
OBJCOPY=riscv64-unknown-elf-objcopy
CFLAGS=-mcmodel=medany -std=gnu99 -O2 -fno-common -fno-builtin-printf -Wall
LDFLAGS=-static -nostdlib -nostartfiles -lgcc

PROGRAMS = rocc-test 

default: $(addsuffix .riscv,$(PROGRAMS))

dumps: $(addsuffix .dump,$(PROGRAMS))

%.o: %.S
	$(CC) $(CFLAGS) -D__ASSEMBLY__=1 -c $< -o $@

rocc-test.o: rocc-test.c
	$(CC) $(CFLAGS) -c $< -o $@

%.riscv: %.o crt.o syscalls.o link.ld
	$(CC) -T link.ld $(LDFLAGS) $< crt.o syscalls.o -o $@

%.dump: %.riscv
	$(OBJDUMP) -D $< > $@

clean:
	rm -f *.riscv *.o *.dump
