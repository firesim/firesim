// See LICENSE for license details.
//
// Phase A0: measure the cost of a single MMIO read across PCIe on an AWS F2
// instance.
//
// Why this exists
// ---------------
// The F2 Small Shell has no DMA engine (ERRATA: the XDMA Shell is unsupported),
// so FireSim's F2 driver currently drains FPGA-to-CPU bridge streams with a loop
// of 4-byte fpga_pci_peek() calls over AppPF BAR4 -- see
// sim/midas/src/main/cc/simif_f2.cc:cpu_managed_axi4_read().
//
// Each peek is an uncached, *non-posted* load: the core issues it and stalls
// until the PCIe completion returns. They cannot pipeline. So the achievable
// bandwidth of that path is exactly:
//
//     4 bytes / (MMIO read round-trip latency)
//
// This program measures that latency directly and reports what it implies, so
// the case for moving trace egress onto a PCIM-mastered DMA path can be made
// (or dropped) before any RTL work happens.
//
// Safety
// ------
// This deliberately touches ONLY AppPF BAR0 (the OCL AXI-Lite window), which on
// a FireSim AFI maps to the widget control-register file. That path always
// responds, and the FireSim driver itself reads low BAR0 offsets at startup
// (simif_f2.cc:is_write_ready reads offset 0x4), so reads here are known-safe.
//
// It does NOT touch BAR4. On a FireSim AFI, BAR4 is decoded by the CPU-managed
// stream engine: a read of an empty stream FIFO never returns, which trips the
// shell's 8us PCIS timeout. Per the AWS Shell Interface Specification, once that
// timeout fires "the DMA/PCIS interface may no longer be functional and the
// AFI/Shell must be re-loaded". Measuring the real BAR4 stream path requires a
// running simulation with data queued, which is Phase A1 (instrumented driver),
// not this program.
//
// It performs no writes at all, for the same reason: without knowing the widget
// register map, a poke could trigger a side effect.
//
// Build & run
// -----------
//     source <aws-fpga-firesim-f2>/sdk_setup.sh    # builds sdk/userspace/lib
//     make
//     sudo taskset -c 2 ./pcie_mmio_latency
//
// Any loaded AFI works, including a vanilla FireSim one. No simulation needs to
// be running and no workload is required.

#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <fpga_mgmt.h>
#include <fpga_pci.h>
#include <hal/fpga_common.h>

// Offset read in the latency loops. 0x4 is what simif_f2.cc's is_write_ready()
// polls, so it is known to be decoded and side-effect free on a FireSim AFI.
#define SAFE_OCL_OFFSET 0x4

// Number of distinct offsets used by the strided test. These stay inside the
// first cache line's worth of the CR file, all of which the widget decodes.
#define STRIDE_SLOTS 16
#define STRIDE_BYTES 4

// FireSim's per-stream FPGA-side buffer in the current MegaBoom+TACIT build:
// 6144 entries x 64 B. Used only to turn the measured latency into a concrete
// "how long does one drain take" number.
#define FIRESIM_STREAM_BUFFER_BYTES (6144UL * 64UL)

// Nominal SDE C2H figure from the AWS SDE Hardware Guide, used for the
// comparison line only.
#define PCIM_REFERENCE_GBPS 12.0

struct stats {
  double min_ns;
  double p50_ns;
  double mean_ns;
  double p99_ns;
  double max_ns;
};

static int cmp_double(const void *a, const void *b) {
  double x = *(const double *)a, y = *(const double *)b;
  return (x > y) - (x < y);
}

static inline uint64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

// Cost of the timing call itself, so it can be subtracted from per-sample
// measurements. On a vDSO clock_gettime this is typically 20-25 ns, which is
// small but not negligible against a ~1 us MMIO read.
static double measure_clock_overhead(size_t iters) {
  uint64_t start = now_ns();
  for (size_t i = 0; i < iters; i++) {
    uint64_t t = now_ns();
    __asm__ __volatile__("" : : "r"(t) : "memory");
  }
  uint64_t end = now_ns();
  return (double)(end - start) / (double)iters;
}

static struct stats summarize(double *samples, size_t n) {
  struct stats s;
  qsort(samples, n, sizeof(double), cmp_double);
  double sum = 0.0;
  for (size_t i = 0; i < n; i++)
    sum += samples[i];
  s.min_ns = samples[0];
  s.p50_ns = samples[n / 2];
  s.mean_ns = sum / (double)n;
  s.p99_ns = samples[(size_t)((double)n * 0.99)];
  s.max_ns = samples[n - 1];
  return s;
}

static void print_stats(const char *label, struct stats s) {
  printf("  %-34s min %7.0f  p50 %7.0f  mean %7.0f  p99 %7.0f  max %8.0f  (ns)\n",
         label,
         s.min_ns,
         s.p50_ns,
         s.mean_ns,
         s.p99_ns,
         s.max_ns);
}

// Bulk timing: time the whole loop and divide. Immune to clock_gettime overhead,
// so this is the number to trust for the mean. Percentiles come from the
// per-sample loops below.
static double bulk_peek_ns(pci_bar_handle_t h, size_t iters) {
  uint32_t v = 0;
  uint64_t start = now_ns();
  for (size_t i = 0; i < iters; i++) {
    if (fpga_pci_peek(h, SAFE_OCL_OFFSET, &v) != 0) {
      fprintf(stderr, "fpga_pci_peek failed mid-loop\n");
      exit(1);
    }
    __asm__ __volatile__("" : : "r"(v) : "memory");
  }
  uint64_t end = now_ns();
  return (double)(end - start) / (double)iters;
}

static double bulk_raw_ns(volatile uint32_t *p, size_t iters) {
  uint32_t v = 0;
  uint64_t start = now_ns();
  for (size_t i = 0; i < iters; i++) {
    v = *p;
    __asm__ __volatile__("" : : "r"(v) : "memory");
  }
  uint64_t end = now_ns();
  return (double)(end - start) / (double)iters;
}

// Reads that walk across distinct addresses. If the platform could overlap
// non-posted reads at all, this would be measurably faster per read than the
// single-address loop. On x86 it will not be -- that is the point.
static double bulk_strided_ns(volatile uint32_t *base, size_t iters) {
  uint32_t v = 0;
  uint64_t start = now_ns();
  for (size_t i = 0; i < iters; i++) {
    v = base[(i % STRIDE_SLOTS) * (STRIDE_BYTES / sizeof(uint32_t))];
    __asm__ __volatile__("" : : "r"(v) : "memory");
  }
  uint64_t end = now_ns();
  return (double)(end - start) / (double)iters;
}

static void per_sample_peek(pci_bar_handle_t h,
                            size_t iters,
                            double clock_ns,
                            double *out) {
  uint32_t v = 0;
  for (size_t i = 0; i < iters; i++) {
    uint64_t t0 = now_ns();
    fpga_pci_peek(h, SAFE_OCL_OFFSET, &v);
    uint64_t t1 = now_ns();
    double d = (double)(t1 - t0) - clock_ns;
    out[i] = d < 0.0 ? 0.0 : d;
  }
}

static void per_sample_raw(volatile uint32_t *p,
                           size_t iters,
                           double clock_ns,
                           double *out) {
  uint32_t v = 0;
  for (size_t i = 0; i < iters; i++) {
    uint64_t t0 = now_ns();
    v = *p;
    __asm__ __volatile__("" : : "r"(v) : "memory");
    uint64_t t1 = now_ns();
    double d = (double)(t1 - t0) - clock_ns;
    out[i] = d < 0.0 ? 0.0 : d;
  }
}

static void usage(const char *prog) {
  printf("usage: %s [-s slot] [-n iterations]\n", prog);
  printf("  -s slot        FPGA slot id (default 0)\n");
  printf("  -n iterations  reads per measurement (default 100000)\n");
}

int main(int argc, char **argv) {
  int slot_id = 0;
  size_t iters = 100000;

  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "-s") && i + 1 < argc) {
      slot_id = atoi(argv[++i]);
    } else if (!strcmp(argv[i], "-n") && i + 1 < argc) {
      iters = strtoul(argv[++i], NULL, 10);
    } else {
      usage(argv[0]);
      return 1;
    }
  }

  if (slot_id < 0 || slot_id >= FPGA_SLOT_MAX) {
    fprintf(stderr, "invalid slot id %d\n", slot_id);
    return 1;
  }
  if (iters < 1000) {
    fprintf(stderr, "use at least 1000 iterations for stable percentiles\n");
    return 1;
  }

  if (fpga_mgmt_init() != 0) {
    fprintf(stderr, "fpga_mgmt_init failed. Are you running as root?\n");
    return 1;
  }

  struct fpga_mgmt_image_info info;
  memset(&info, 0, sizeof(info));
  if (fpga_mgmt_describe_local_image(slot_id, &info, 0) != 0) {
    fprintf(stderr,
            "Cannot describe slot %d. Is an AFI loaded, and are you root?\n",
            slot_id);
    return 1;
  }
  if (info.status != FPGA_STATUS_LOADED) {
    fprintf(stderr, "Slot %d has no AFI in the LOADED state.\n", slot_id);
    return 1;
  }

  printf("=== AWS F2 MMIO read latency (Phase A0) ===\n");
  printf("slot %d, AFI %s\n",
         slot_id,
         info.ids.afi_id[0] ? info.ids.afi_id : "(none)");
  printf("AppPF BAR0 (OCL), offset 0x%x, %zu reads per measurement\n\n",
         SAFE_OCL_OFFSET,
         iters);

  pci_bar_handle_t bar0 = PCI_BAR_HANDLE_INIT;
  // No BURST_CAPABLE: we want plain uncached MMIO semantics, not write-combining.
  if (fpga_pci_attach(slot_id, FPGA_APP_PF, APP_PF_BAR0, 0, &bar0) != 0) {
    fprintf(stderr, "fpga_pci_attach BAR0 failed: %s\n", strerror(errno));
    return 1;
  }

  void *mapped = NULL;
  if (fpga_pci_get_address(bar0,
                           0,
                           STRIDE_SLOTS * (STRIDE_BYTES / sizeof(uint32_t)),
                           &mapped) != 0) {
    fprintf(stderr, "fpga_pci_get_address failed\n");
    fpga_pci_detach(bar0);
    return 1;
  }
  volatile uint32_t *bar0_base = (volatile uint32_t *)mapped;
  volatile uint32_t *safe_word =
      bar0_base + (SAFE_OCL_OFFSET / sizeof(uint32_t));

  double clock_ns = measure_clock_overhead(100000);
  printf("clock_gettime overhead: %.1f ns (subtracted from per-sample "
         "measurements)\n\n",
         clock_ns);

  // Warm up: fault in the mapping and settle any frequency scaling.
  (void)bulk_peek_ns(bar0, 10000);

  printf("Bulk timing (whole loop / iterations -- trust these means):\n");
  double peek_ns = bulk_peek_ns(bar0, iters);
  double raw_ns = bulk_raw_ns(safe_word, iters);
  double strided_ns = bulk_strided_ns(bar0_base, iters);
  printf("  %-34s %7.1f ns/read\n", "fpga_pci_peek()", peek_ns);
  printf("  %-34s %7.1f ns/read\n", "raw volatile load", raw_ns);
  printf("  %-34s %7.1f ns/read\n",
         "raw load, 16 distinct offsets",
         strided_ns);
  printf("  %-34s %7.1f ns\n\n",
         "library overhead (peek - raw)",
         peek_ns - raw_ns);

  double *samples = malloc(iters * sizeof(double));
  if (!samples) {
    fprintf(stderr, "out of memory\n");
    fpga_pci_detach(bar0);
    return 1;
  }

  printf("Per-sample distribution:\n");
  per_sample_peek(bar0, iters, clock_ns, samples);
  print_stats("fpga_pci_peek()", summarize(samples, iters));
  per_sample_raw(safe_word, iters, clock_ns, samples);
  print_stats("raw volatile load", summarize(samples, iters));
  free(samples);

  // Use the bulk raw-load number as the physical round-trip cost; it is the
  // cleanest estimate of what the hardware can do.
  double rt_ns = raw_ns;
  double bytes_per_s = 4.0 / (rt_ns * 1e-9);
  double drain_reads = (double)FIRESIM_STREAM_BUFFER_BYTES / 4.0;
  double drain_ms = drain_reads * rt_ns * 1e-6;
  double pcim_drain_ms =
      ((double)FIRESIM_STREAM_BUFFER_BYTES / (PCIM_REFERENCE_GBPS * 1e9)) * 1e3;
  double pcim_total_ms = pcim_drain_ms + (rt_ns * 1e-6); // one poll + the DMA

  printf("\n=== Implications for FireSim on F2 ===\n");
  printf("Measured MMIO read round trip      : %.0f ns\n", rt_ns);
  printf("Strided vs single-address delta    : %+.1f%%  ",
         100.0 * (strided_ns - raw_ns) / raw_ns);
  printf("(near zero => non-posted reads do not overlap)\n\n");

  printf("Today (4-byte peeks over BAR4):\n");
  printf("  effective C2H bandwidth          : %.2f MB/s\n",
         bytes_per_s / 1e6);
  printf("  reads to drain one %zu KiB buffer : %.0f\n",
         FIRESIM_STREAM_BUFFER_BYTES / 1024,
         drain_reads);
  printf("  wall clock for that drain        : %.1f ms\n\n", drain_ms);

  printf("PCIM-mastered DMA (FPGAManagedStream), at %.0f GB/s reference:\n",
         PCIM_REFERENCE_GBPS);
  printf("  MMIO reads per drain             : 1 (the bytesAvailable poll)\n");
  printf("  wall clock for the same drain    : %.3f ms\n", pcim_total_ms);
  printf("  speedup on trace egress          : %.0fx\n\n",
         drain_ms / pcim_total_ms);

  printf("Note: the PCIM figure is the AWS SDE Hardware Guide's nominal C2H\n");
  printf("number, not a measurement. Treat it as the ceiling to aim at, and\n");
  printf("re-measure once the PCIM path exists (Phase E).\n");

  fpga_pci_detach(bar0);
  return 0;
}
