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

static inline double timediff(struct timespec *ts1, struct timespec *ts2)
{
	return (ts2->tv_sec - ts1->tv_sec) * 1e9 + (ts2->tv_nsec - ts1->tv_nsec);
}

int main(void)
{
	long cycles;
	struct timespec ts1, ts2;
	double nanos;
	double cycles_per_nano;

	clock_gettime(CLOCK_REALTIME, &ts1);
	cycles = -rdcycle();

	// We have to busy-wait instead of using sleep because
	// WFI pauses the cycle count
	do {
		clock_gettime(CLOCK_REALTIME, &ts2);
	} while (timediff(&ts1, &ts2) < 1e9);

	cycles += rdcycle();
	nanos = timediff(&ts1, &ts2);
	cycles_per_nano = cycles / nanos;

	printf("Clock speed: %f GHz\n", cycles_per_nano);
	return 0;
}
