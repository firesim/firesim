#include <stdint.h>

#ifndef CTC_CHIP_ID
#error CTC_CHIP_ID must be 0 or 1
#endif

// CLINT mtimecmp[0] is an uncached, read/write MMIO register. The CTC outward
// translator strips 0x1000000000 when forwarding a remote access to the peer.
#define LOCAL_SIGNAL  ((volatile uint32_t *)0x02004000UL)
#define REMOTE_SIGNAL ((volatile uint32_t *)0x1002004000ULL)
#define LOCAL_DATA    ((volatile uint32_t *)0x90000000UL)
#define REMOTE_DATA   ((volatile uint32_t *)0x1090000000ULL)
#define LOCAL_DATA64  ((volatile uint64_t *)0x90000100UL)
#define REMOTE_DATA64 ((volatile uint64_t *)0x1090000100ULL)
#define WORDS 16
#define REQUEST 0x43544331U
#define REPLY   0x43544332U
#define DONE    0x43544333U
#define TIMEOUT_CYCLES 1000000ULL

static inline uint64_t cycle(void) {
  uint64_t value;
  __asm__ volatile("rdcycle %0" : "=r"(value));
  return value;
}

#if CTC_CHIP_ID == 1
static void delay_cycles(uint64_t cycles) {
  const uint64_t start = cycle();
  while (cycle() - start < cycles) {}
}
#endif

static int wait_for(volatile uint32_t *address, uint32_t expected) {
  const uint64_t start = cycle();
  while (*address != expected) {
    if (cycle() - start > TIMEOUT_CYCLES) return -1;
  }
  return 0;
}

static uint32_t pattern(unsigned i, unsigned side) {
  return 0x6d39a0f5U ^ (side << 24) ^ (i * 0x10204081U);
}

static uint64_t pattern64(unsigned i, unsigned side) {
  return 0x1122334455667788ULL ^ (side ? 0xa5c39e4172d4b680ULL : 0ULL)
      ^ (i * 0x0102040810204081ULL);
}

int main(void) {
#if CTC_CHIP_ID == 0
  for (unsigned i = 0; i < WORDS; ++i) {
    REMOTE_DATA[i] = pattern(i, 0);
    REMOTE_DATA64[i] = pattern64(i, 0);
  }
  __sync_synchronize();
  *REMOTE_SIGNAL = REQUEST;
  __sync_synchronize();
  if (wait_for(LOCAL_SIGNAL, REPLY)) return 1;
  for (unsigned i = 0; i < WORDS; ++i) {
    if (LOCAL_DATA[WORDS + i] != pattern(i, 1)) return 4;
    if (LOCAL_DATA64[WORDS + i] != pattern64(i, 1)) return 6;
  }
  *REMOTE_SIGNAL = DONE;
  __sync_synchronize();
#else
  if (wait_for(LOCAL_SIGNAL, REQUEST)) return 2;
  for (unsigned i = 0; i < WORDS; ++i) {
    if (LOCAL_DATA[i] != pattern(i, 0)) return 5;
    if (LOCAL_DATA64[i] != pattern64(i, 0)) return 7;
  }
  for (unsigned i = 0; i < WORDS; ++i) {
    REMOTE_DATA[WORDS + i] = pattern(i, 1);
    REMOTE_DATA64[WORDS + i] = pattern64(i, 1);
  }
  __sync_synchronize();
  *REMOTE_SIGNAL = REPLY;
  __sync_synchronize();
  if (wait_for(LOCAL_SIGNAL, DONE)) return 3;
  delay_cycles(100000);
#endif
  return 0;
}
