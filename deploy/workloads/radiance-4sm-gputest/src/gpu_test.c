// Two-board Radiance GPU smoke test, 4-SM RadianceHBMConfig variant (Rocket side). The Muon kernel at system address
// 0x1_1000_0000 (Muon 0x1000_0000) has each core write MAGIC|mhartid to results[mhartid] and
// retire its warp. Rocket clears the slots, releases the GPU soft reset, waits for every core to
// report finished, then checks the slots and exits via HTIF (0 = pass).
#include <stdio.h>
#include <stdint.h>
#include <riscv-pk/encoding.h>

#define GPU_RESET      ((volatile uint32_t *)0x41000000UL)
#define GPU_ALL_DONE   ((volatile uint32_t *)0x41000008UL)
#define GPU_CORE_DONE(i) ((volatile uint32_t *)(0x41000010UL + 4UL * (i)))
#define RESULTS        ((volatile uint32_t *)0x110100000UL)
#define NSLOTS         1024        /* mhartid = cluster<<8 | core<<7 | warp<<4 | lane (4 clusters, 2 cores, 8 warps, 16 lanes) */
#define MAGIC          0xC0DE0000u
#define NCLUSTERS      4
#define NCORES         2
#define NLANES         16          /* warp 0 boots with all 16 lanes active; each lane writes its own slot */
#define TIMEOUT_CYCLES 60000000UL  /* ~200 s at a ~0.3 MHz effective target clock */
#define SETTLE_CYCLES  200000UL

static inline uint64_t cycles(void) { return read_csr(cycle); }
static void spin(uint64_t n) { uint64_t t0 = cycles(); while (cycles() - t0 < n) ; }

int main(void) {
  for (int i = 0; i < NSLOTS; i++) RESULTS[i] = 0;
  asm volatile("fence" ::: "memory");
  printf("gpu-test: slots cleared, releasing GPU soft reset at cycle %lu\n", (unsigned long)cycles());
  *GPU_RESET = 1;
  asm volatile("fence" ::: "memory");
  *GPU_RESET = 0;
  asm volatile("fence" ::: "memory");

  uint64_t t0 = cycles(), tnow;
  int timed_out = 0;
  while (*GPU_ALL_DONE == 0) {
    tnow = cycles();
    if (tnow - t0 > TIMEOUT_CYCLES) { timed_out = 1; break; }
  }
  tnow = cycles();
  printf("gpu-test: all_done=%u after %lu cycles%s\n", *GPU_ALL_DONE, (unsigned long)(tnow - t0),
         timed_out ? " (TIMEOUT)" : "");
  for (int i = 0; i < 8; i++) printf("gpu-test: core %d finished=%u\n", i, *GPU_CORE_DONE(i));

  spin(SETTLE_CYCLES);
  asm volatile("fence" ::: "memory");
  int groups_ok = 0, unexpected = 0;
  for (int c = 0; c < NCLUSTERS; c++)
    for (int k = 0; k < NCORES; k++) {
      int base = (c << 8) | (k << 7), ok = 0;
      for (int l = 0; l < NLANES; l++)
        if (RESULTS[base + l] == (MAGIC | (uint32_t)(base + l))) ok++;
      printf("gpu-test: cluster %d core %d (harts %d..%d): %d/%d lanes wrote their id%s\n",
             c, k, base, base + NLANES - 1, ok, NLANES, ok == NLANES ? "" : "  <-- MISSING");
      if (ok == NLANES) groups_ok++;
    }
  for (int i = 0; i < NSLOTS; i++) {
    uint32_t v = RESULTS[i];
    int lane = i & 0xf, warp = (i >> 4) & 0x7;
    int expected_slot = (warp == 0) && (lane < NLANES);
    if (v != 0 && !expected_slot) { unexpected++; if (unexpected <= 8) printf("gpu-test: unexpected results[%d] = 0x%08x\n", i, v); }
    if (v != 0 && expected_slot && v != (MAGIC | (uint32_t)i)) { unexpected++; if (unexpected <= 8) printf("gpu-test: bad results[%d] = 0x%08x\n", i, v); }
  }
  printf("gpu-test: %d/%d core groups complete, %d unexpected slots\n", groups_ok, NCLUSTERS * NCORES, unexpected);
  if (!timed_out && groups_ok == NCLUSTERS * NCORES && unexpected == 0) {
    printf("gpu-test: PASS\n");
    return 0;
  }
  printf("gpu-test: FAIL\n");
  return 1;
}
