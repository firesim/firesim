// See LICENSE for license details.
//
// Phase C dry run: exercise the host-memory setup that simif_f2 performs for
// PCIM-mastered bridge streams, without needing the FireSim bitstream.
//
// Why this exists
// ---------------
// simif_f2_t::allocate_to_cpu_buffer() and check_bus_master_enabled() depend
// only on the AWS SDK and a loaded AFI, not on any FireSim custom logic. But
// metasimulation substitutes its own FPGAManagedStreamIO, so neither has ever
// run. This performs the same steps in the same order so their failure modes
// surface on a cheap instance-hour rather than during bitstream bring-up.
//
// It cannot test that DMA actually lands -- that needs the CL. What it does
// cover is every way the setup can fail *before* a single byte moves:
//
//   1. Bus Master Enable. Without it the shell silently drops every PCIM
//      write, so the FPGA appears to run while no data reaches host memory.
//
//   2. Hugepage availability. One 2MiB page is mapped per stream.
//
//   3. Physical address resolution. fpga_dma_mem_map_huge reads
//      /proc/self/pagemap and checks only that the read returned 8 bytes --
//      not the page-present bit, and not whether the frame number is zero.
//      Since Linux 4.0 an unprivileged reader gets a zeroed frame number and
//      the read still succeeds, so without CAP_SYS_ADMIN the call reports
//      success and hands back physical address 0. Programming that into the
//      stream engine would aim FPGA writes at low physical memory.
//
//   4. Distinctness. Streams must not be handed overlapping regions.
//
// Build & run
// -----------
//     source <aws-fpga-firesim-f2>/sdk_setup.sh
//     make dma_buffer_check
//     sudo ./dma_buffer_check
//
// Any loaded AFI works; no simulation need be running. Reserve hugepages
// first, e.g. `sudo sysctl -w vm.nr_hugepages=8`.

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <fpga_dma_mem.h>
#include <fpga_mgmt.h>
#include <fpga_pci.h>
#include <hal/fpga_common.h>

// Matches the constant in simif_f2.cc.
#define HUGE_PAGE_BYTES (2 * 1024 * 1024)

// The MegaBoom+TACIT config carries two to-host streams (TracerV and TACIT),
// each with a 512KiB FPGA-side buffer. Default to that shape.
#define DEFAULT_STREAMS 2
#define DEFAULT_STREAM_BYTES (512 * 1024)

struct buffer {
  uint64_t va;
  uint64_t pa;
};

static int failures = 0;

static void check(int ok, const char *what, const char *detail) {
  printf("  [%s] %s%s%s\n",
         ok ? "PASS" : "FAIL",
         what,
         detail ? ": " : "",
         detail ? detail : "");
  if (!ok)
    failures++;
}

/** Same check simif_f2_t::check_bus_master_enabled performs. */
static void check_bus_master(const struct fpga_pci_resource_map *map) {
  char path[256];
  snprintf(path,
           sizeof(path),
           "/sys/bus/pci/devices/%04x:%02x:%02x.%d/config",
           map->domain,
           map->bus,
           map->dev,
           map->func);

  FILE *fp = fopen(path, "rb");
  if (!fp) {
    check(0, "bus mastering", "cannot open PCI config space");
    return;
  }

  uint16_t command = 0;
  int ok = (fseek(fp, 0x4, SEEK_SET) == 0) && (fread(&command, 2, 1, fp) == 1);
  fclose(fp);

  if (!ok) {
    check(0, "bus mastering", "cannot read PCI COMMAND register");
    return;
  }

  if (command & 0x4) {
    check(1, "bus mastering enabled", NULL);
  } else {
    char msg[256];
    snprintf(msg,
             sizeof(msg),
             "BME clear; enable with `sudo setpci -s %04x:%02x:%02x.%d 4.w=6`",
             map->domain,
             map->bus,
             map->dev,
             map->func);
    check(0, "bus mastering enabled", msg);
  }
}

int main(int argc, char **argv) {
  int slot_id = 0;
  size_t num_streams = DEFAULT_STREAMS;
  size_t stream_bytes = DEFAULT_STREAM_BYTES;

  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "-s") && i + 1 < argc) {
      slot_id = atoi(argv[++i]);
    } else if (!strcmp(argv[i], "-n") && i + 1 < argc) {
      num_streams = strtoul(argv[++i], NULL, 10);
    } else if (!strcmp(argv[i], "-b") && i + 1 < argc) {
      stream_bytes = strtoul(argv[++i], NULL, 10);
    } else {
      printf("usage: %s [-s slot] [-n streams] [-b bytes-per-stream]\n",
             argv[0]);
      return 1;
    }
  }

  if (slot_id < 0 || slot_id >= FPGA_SLOT_MAX || num_streams == 0) {
    fprintf(stderr, "invalid slot or stream count\n");
    return 1;
  }

  printf("=== F2 PCIM host-buffer dry run (Phase C) ===\n");
  printf("slot %d, %zu stream(s), %zu bytes each\n\n", slot_id, num_streams,
         stream_bytes);

  if (geteuid() != 0) {
    printf("  [WARN] not running as root; pagemap will report frame 0 and the\n"
           "         physical-address check below is expected to fail.\n\n");
  }

  if (fpga_mgmt_init() != 0) {
    fprintf(stderr, "fpga_mgmt_init failed\n");
    return 1;
  }

  struct fpga_mgmt_image_info info;
  memset(&info, 0, sizeof(info));
  if (fpga_mgmt_describe_local_image(slot_id, &info, 0) != 0) {
    fprintf(stderr, "Cannot describe slot %d. Is an AFI loaded, and are you "
                    "root?\n",
            slot_id);
    return 1;
  }
  if (info.status != FPGA_STATUS_LOADED) {
    fprintf(stderr, "Slot %d has no AFI in the LOADED state.\n", slot_id);
    return 1;
  }
  printf("AFI: %s\n\n", info.ids.afi_id[0] ? info.ids.afi_id : "(none)");

  printf("Shell prerequisites:\n");
  check_bus_master(&info.spec.map[FPGA_APP_PF]);

  printf("\nPer-stream buffers:\n");
  struct buffer *bufs = calloc(num_streams, sizeof(*bufs));
  if (!bufs) {
    fprintf(stderr, "out of memory\n");
    return 1;
  }

  size_t mapped = 0;
  for (size_t i = 0; i < num_streams; i++) {
    if (stream_bytes > HUGE_PAGE_BYTES) {
      check(0, "stream fits in one hugepage", "reduce fpgaBufferDepth");
      break;
    }

    int rc = fpga_dma_mem_map_huge(&bufs[i].va, &bufs[i].pa);
    if (rc) {
      char msg[160];
      snprintf(msg, sizeof(msg),
               "rc=%d; reserve pages with `sudo sysctl -w vm.nr_hugepages=%zu`",
               rc, num_streams + 2);
      check(0, "hugepage mapped", msg);
      break;
    }
    mapped++;

    char detail[160];
    snprintf(detail, sizeof(detail), "va 0x%" PRIx64 " -> pa 0x%" PRIx64,
             bufs[i].va, bufs[i].pa);
    check(1, "hugepage mapped", detail);

    // The guard simif_f2 applies: a zero physical address means pagemap gave
    // back a masked frame number rather than a real one.
    check(bufs[i].pa != 0,
          "physical address resolved",
          bufs[i].pa ? NULL : "got 0 -- needs CAP_SYS_ADMIN for pagemap");

    check((bufs[i].pa % HUGE_PAGE_BYTES) == 0,
          "physical address hugepage-aligned",
          NULL);

    // Prove the region is actually usable from the CPU side.
    memset((void *)bufs[i].va, 0xA5, stream_bytes);
    const uint8_t *p = (const uint8_t *)bufs[i].va;
    int intact = (p[0] == 0xA5) && (p[stream_bytes / 2] == 0xA5) &&
                 (p[stream_bytes - 1] == 0xA5);
    check(intact, "buffer readable and writable", NULL);
  }

  if (mapped > 1) {
    printf("\nDistinctness:\n");
    int overlap = 0;
    for (size_t i = 0; i < mapped && !overlap; i++)
      for (size_t j = i + 1; j < mapped; j++)
        if (bufs[i].pa == bufs[j].pa ||
            (bufs[i].pa < bufs[j].pa + HUGE_PAGE_BYTES &&
             bufs[j].pa < bufs[i].pa + HUGE_PAGE_BYTES)) {
          overlap = 1;
          break;
        }
    check(!overlap, "stream buffers do not overlap", NULL);
  }

  for (size_t i = 0; i < mapped; i++)
    fpga_dma_mem_unmap(&bufs[i].va, HUGE_PAGE_BYTES);
  free(bufs);

  printf("\n%s (%d failure%s)\n",
         failures ? "FAILED" : "All checks passed",
         failures,
         failures == 1 ? "" : "s");
  printf("\nNot covered: whether the FPGA's writes actually land in these\n"
         "buffers. That needs the PCIM-enabled bitstream.\n");

  return failures ? 1 : 0;
}
