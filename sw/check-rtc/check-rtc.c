#include <stdlib.h>
#include <stdio.h>
#include "util.h"
#include "mmio.h"

#define MTIME_ADDR 0x0200bff8L

int main(void)
{
	unsigned long cycle;
	unsigned long time;

	do {
		time = reg_read64(MTIME_ADDR);
	} while (time < 100);

	cycle = rdcycle();

	printf("%ld cycles per jiffy\n", cycle / time);

	return 0;
}
