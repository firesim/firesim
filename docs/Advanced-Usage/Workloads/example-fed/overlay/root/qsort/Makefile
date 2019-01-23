CC = riscv64-unknown-linux-gnu-gcc
CFLAGS := -O3 -static -DRISCV

#CC = gcc
#CFLAGS := -O3 -std=gnu99

qsort: qsort_main.c util.h
	${CC} ${CFLAGS} -o qsort qsort_main.c

clean:
	rm -f qsort
