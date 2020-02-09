
CC := riscv64-unknown-linux-gnu-gcc
CFLAGS := -nostartfiles
LDFLAGS := -nostdlib

bin := start end

.PHONY: all
all: $(bin)

$(bin): %: %.S
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

.SUFFIXES:
