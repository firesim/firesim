#include <stdlib.h>
#include <stdio.h>
#include "util.h"
#include "mmio.h"

#define MTIME_ADDR 0x0200bff8L

int main(void)
{
	unsigned long startcycle, cycle;
	unsigned long starttime, time;

	startcycle = rdcycle();
	starttime = reg_read64(MTIME_ADDR);

	asm volatile ("addi x0, x1, 0");

	do {
		time = reg_read64(MTIME_ADDR);
	} while ((time - starttime) < 100);

	asm volatile ("addi x0, x2, 0");

	time -= starttime;
	cycle = rdcycle() - startcycle;

	printf("%ld cycles per jiffy\n", cycle / time);

	return 0;
}
