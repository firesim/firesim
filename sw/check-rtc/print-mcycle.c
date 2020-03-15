#include <stdio.h>
#include <time.h>

static inline long rdcycle(void)
{
    long cycle;
    asm volatile ("csrr %[cycle], cycle" : [cycle] "=r" (cycle));
    return cycle;
}

int main(void)
{
    struct timespec ts1;
    printf("Cycles elapsed: %lu\n", rdcycle());
    clock_gettime(CLOCK_REALTIME, &ts1);
    printf("Time elapsed: %lu.%.9lu seconds\n", ts1.tv_sec, ts1.tv_nsec);
    return 0;
}
