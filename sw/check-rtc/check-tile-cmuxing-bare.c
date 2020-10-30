#include <stdlib.h>
#include <stdio.h>
#include "util.h"
#include "mmio.h"

#define MTIME_ADDR 0x0200bff8L
#define CMUX_ADDR 0x0101000

void do_work(int iteration) {
	unsigned long startcycle, cycle;
	unsigned long starttime, time;

	startcycle = rdcycle();
	starttime = reg_read64(MTIME_ADDR);

	asm volatile ("addi x0, x1, 0");

	do {
		time = reg_read64(MTIME_ADDR);
	} while ((time - starttime) < 10);


	time -= starttime;
	cycle = rdcycle() - startcycle;

	printf("Interval %d: %ld cycles for MTIME delta of %ld\n", iteration, cycle, time);

}

int main(void)
{
    do_work(0);
    reg_write8(CMUX_ADDR, 0xFF);
    do_work(1);

	return 0;
}
