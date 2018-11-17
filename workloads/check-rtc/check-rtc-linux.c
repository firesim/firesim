#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

static inline long rdcycle(void)
{
	long cycle;
	asm volatile ("csrr %[cycle], cycle" : [cycle] "=r" (cycle));
	return cycle;
}

int main(void)
{
	long cycles;
	struct timespec ts1, ts2;
	double nanos;
	double cycles_per_nano;

	clock_gettime(CLOCK_REALTIME, &ts1);
	cycles = -rdcycle();

	sleep(1);

	clock_gettime(CLOCK_REALTIME, &ts2);
	cycles += rdcycle();

	nanos = (ts2.tv_sec - ts1.tv_sec) * 1e9 + (ts2.tv_nsec - ts1.tv_nsec);
	cycles_per_nano = cycles / nanos;

	printf("Clock speed: %f GHz\n", cycles_per_nano);
	return 0;
}
