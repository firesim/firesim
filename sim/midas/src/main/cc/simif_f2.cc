#include <cassert>
#include <cinttypes>
#include <cstring>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/cpu_managed_stream.h"
#include "bridges/fpga_managed_stream.h"
#include "core/simif.h"

#include <fpga_dma_mem.h>
#include <fpga_mgmt.h>
#include <fpga_pci.h>

class simif_f2_t final : public simif_t, public BiDirectionalManagedStreamIO {
public:
  simif_f2_t(const TargetConfig &config, const std::vector<std::string> &args);
  ~simif_f2_t();

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  uint32_t is_write_ready();
  void check_rc(int rc, char *infostr);
  void fpga_shutdown();
  void fpga_setup(int slot_id, const std::string &agfi);

  CPUManagedStreamIO &get_cpu_managed_stream_io() override { return *this; }
  FPGAManagedStreamIO &get_fpga_managed_stream_io() override { return *this; }

  /** Abort unless the FPGA is allowed to master the bus (see definition). */
  void check_bus_master_enabled(const struct fpga_pci_resource_map &map);

private:
  uint32_t mmio_read(size_t addr) override { return read(addr); }
  void mmio_write(size_t addr, uint32_t value) override {
    return write(addr, value);
  }
  size_t
  cpu_managed_axi4_write(size_t addr, const char *data, size_t size) override;
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size) override;
  uint64_t get_beat_bytes() const override {
    return config.cpu_managed->beat_bytes();
  }
  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t size) override;

  /** Hugepages mapped for FPGA-managed streams, unmapped on teardown. */
  std::vector<std::pair<uint64_t, size_t>> dma_buffers;

  // int edma_write_fd; // rh: i'm leaving this in as a reminder that the beta starts soon and all this work will be for nothing
  // int edma_read_fd;
  pci_bar_handle_t pci_bar_handle;
  pci_bar_handle_t pci_bar4_handle;
};

simif_f2_t::simif_f2_t(const TargetConfig &config,
                       const std::vector<std::string> &args)
    : simif_t(config) {

  int slot_id = -1;
  std::string agfi;
  for (auto &arg : args) {
    if (arg.find("+slotid=") == 0) {
      slot_id = atoi((arg.c_str()) + 8);
      continue;
    }
    if (arg.find("+agfi=") == 0) {
      agfi += arg.c_str() + 6;
      if (agfi.find("agfi-") != 0 && agfi.size() != 22) {
        throw std::runtime_error("invalid AGFI: " + agfi);
      }
      continue;
    }
  }

  if (slot_id == -1) {
    fprintf(stderr, "Slot ID not specified. Assuming Slot 0\n");
    slot_id = 0;
  }

  fpga_setup(slot_id, agfi);
}

void simif_f2_t::check_rc(int rc, char *infostr) {
  if (rc) {
    if (infostr) {
      fprintf(stderr, "%s\n", infostr);
    }
    fprintf(stderr, "INVALID RETCODE: %d\n", rc);
    fpga_shutdown();
    exit(1);
  }
}

void simif_f2_t::fpga_shutdown() {
  int rc = fpga_pci_detach(pci_bar_handle);
  // don't call check_rc because of fpga_shutdown call. do it manually:
  if (rc) {
    fprintf(stderr, "Failure while detaching from the fpga (BAR0): %d\n", rc);
  }
  rc = fpga_pci_detach(pci_bar4_handle);
  if (rc) {
    fprintf(stderr, "Failure while detaching from the fpga (BAR4): %d\n", rc);
  }
}

/**
 * Amazon PCI Vendor ID.
 */
constexpr uint16_t pci_vendor_id = 0x1D0F;

/**
 * Amazon PCI Device ID pre-assigned by for f2 applications.
 */
constexpr uint16_t pci_device_id = 0xF002;

void simif_f2_t::fpga_setup(int slot_id, const std::string &agfi) {
  int rc = fpga_mgmt_init();
  check_rc(rc, "fpga_mgmt_init FAILED");

  // If an AGFI was specified, re-load the image.
  if (!agfi.empty()) {
    fprintf(stderr, "Flashing AGFI: %s\n", agfi.c_str());

    // Clear the existing image. Wait up to 10 seconds.
    rc = fpga_mgmt_clear_local_image_sync(slot_id, 10, 1000, nullptr);
    // rc = 0; // fpga_mgmt_clear_local_image_sync(slot_id, 10, 1000, nullptr);
    check_rc(rc, "Cannot clear image");

    // Load the image.
    std::unique_ptr<char[]> data(new char[agfi.size() + 1]);
    memcpy(data.get(), agfi.c_str(), agfi.size() + 1);
    // rc = 0; // fpga_mgmt_load_local_image(slot_id, data.get());
    rc = fpga_mgmt_load_local_image(slot_id, data.get());
    check_rc(rc, "Cannot load AGFI");

    // Wait and poll as long as the slot is busy.
    int status;
    do {
      sleep(1);

      struct fpga_mgmt_image_info info = {0};
      rc = fpga_mgmt_describe_local_image(slot_id, &info, 0);
      check_rc(rc, "Unable to get AFI information from slot.");
      status = info.status;
    } while (status == FPGA_STATUS_BUSY);
  }

  /* check AFI status */
  struct fpga_mgmt_image_info info = {0};

  /* get local image description, contains status, vendor id, and device id. */
  rc = fpga_mgmt_describe_local_image(slot_id, &info, 0);
  check_rc(rc,
           "Unable to get AFI information from slot. Are you running as root?");

  /* check to see if the slot is ready */
  if (info.status != FPGA_STATUS_LOADED) {
    rc = 1;
    check_rc(rc, "AFI in Slot is not in READY state !");
  }

  fprintf(stderr,
          "AFI ID for Slot %2u: %s\n",
          slot_id,
          (!info.ids.afi_id[0]) ? "none" : info.ids.afi_id);

  fprintf(stderr,
          "AFI PCI  Vendor ID: 0x%x, Device ID 0x%x\n",
          info.spec.map[FPGA_APP_PF].vendor_id,
          info.spec.map[FPGA_APP_PF].device_id);

  /* confirm that the AFI that we expect is in fact loaded */
  if (info.spec.map[FPGA_APP_PF].vendor_id != pci_vendor_id ||
      info.spec.map[FPGA_APP_PF].device_id != pci_device_id) {
    fprintf(
        stderr,
        "AFI does not show expected PCI vendor id and device ID. If the AFI "
        "was just loaded, it might need a rescan. Rescanning now.\n");

    rc = fpga_pci_rescan_slot_app_pfs(slot_id);
    check_rc(rc, "Unable to update PF for slot");
    /* get local image description, contains status, vendor id, and device id.
     */
    rc = fpga_mgmt_describe_local_image(slot_id, &info, 0);
    check_rc(rc, "Unable to get AFI information from slot");

    fprintf(stderr,
            "AFI ID for Slot %2u: %s\n",
            slot_id,
            (!info.ids.afi_id[0]) ? "none" : info.ids.afi_id);

    fprintf(stderr,
            "AFI PCI  Vendor ID: 0x%x, Device ID 0x%x\n",
            info.spec.map[FPGA_APP_PF].vendor_id,
            info.spec.map[FPGA_APP_PF].device_id);

    /* confirm that the AFI that we expect is in fact loaded after rescan */
    if (info.spec.map[FPGA_APP_PF].vendor_id != pci_vendor_id ||
        info.spec.map[FPGA_APP_PF].device_id != pci_device_id) {
      rc = 1;
      check_rc(rc,
               "The PCI vendor id and device of the loaded AFI are not "
               "the expected values.");
    }
  }

  /* PCIM writes are silently dropped without this; check before we rely on it */
  check_bus_master_enabled(info.spec.map[FPGA_APP_PF]);

  /* attach to BAR0 (OCL) */
  pci_bar_handle = PCI_BAR_HANDLE_INIT;
  rc = fpga_pci_attach(slot_id, FPGA_APP_PF, APP_PF_BAR0, 0, &pci_bar_handle);
  check_rc(rc, "fpga_pci_attach BAR0 FAILED");
  printf("Attached to BAR0 (OCL)\n");

  /* rh: attach to BAR4 (for now to do a PCIS cuz no XDMA)*/
  pci_bar4_handle = PCI_BAR_HANDLE_INIT;
  rc = fpga_pci_attach(slot_id, FPGA_APP_PF, APP_PF_BAR4, BURST_CAPABLE, &pci_bar4_handle);
  check_rc(rc, "fpga_pci_attach BAR4 FAILED");
  printf("Attached to BAR4 (PCIS)\n");
}

/**
 * The FPGA masters PCIM writes with *physical* addresses, so a stream buffer
 * has to be physically contiguous for its whole length. Userspace can only get
 * that guarantee from a hugepage -- an ordinary anonymous mapping is contiguous
 * in virtual address space and arbitrarily scattered in physical.
 *
 * fpga_dma_mem_map_huge() maps one default-size (2 MiB) hugepage and reports
 * both addresses. One page per stream: slicing a single page across streams
 * would cap their combined size at 2 MiB, whereas this caps each stream at
 * 2 MiB independently.
 *
 * Requires free hugepages, e.g. `sudo sysctl -w vm.nr_hugepages=<n>`.
 */
FPGAManagedStreams::HostBuffer simif_f2_t::allocate_to_cpu_buffer(size_t size) {
  constexpr size_t huge_page_bytes = 2 * 1024 * 1024;

  if (size > huge_page_bytes) {
    fprintf(stderr,
            "Stream buffer of %zu bytes exceeds the %zu-byte hugepage backing "
            "it. Reduce the bridge's fpgaBufferDepth, or extend this to map "
            "1 GiB hugepages.\n",
            size,
            huge_page_bytes);
    fpga_shutdown();
    exit(1);
  }

  uint64_t virtual_address = 0;
  uint64_t physical_address = 0;
  int rc = fpga_dma_mem_map_huge(&virtual_address, &physical_address);
  if (rc) {
    fprintf(stderr,
            "Could not map a hugepage for a %zu-byte stream buffer (rc=%d). "
            "Are hugepages reserved? Try: sudo sysctl -w vm.nr_hugepages=%zu\n",
            size,
            rc,
            dma_buffers.size() + 4);
    fpga_shutdown();
    exit(1);
  }

  // The FPGA writes into this region before the driver ever reads it, so a
  // stale page would surface as plausible-looking garbage in a trace rather
  // than as an obvious failure.
  memset((void *)virtual_address, 0, size);

  dma_buffers.emplace_back(virtual_address, huge_page_bytes);

  fprintf(stderr,
          "Stream buffer: %zu bytes at va 0x%" PRIx64 " -> pa 0x%" PRIx64 "\n",
          size,
          virtual_address,
          physical_address);

  return {(void *)virtual_address, physical_address};
}

/**
 * Without PCIe Bus Master Enable the shell silently drops every PCIM write, so
 * the FPGA appears to run while no data ever reaches host memory. Fail here
 * rather than let that present as an inexplicably empty stream.
 */
void simif_f2_t::check_bus_master_enabled(
    const struct fpga_pci_resource_map &map) {
  char path[256];
  snprintf(path,
           sizeof(path),
           "/sys/bus/pci/devices/%04x:%02x:%02x.%d/config",
           map.domain,
           map.bus,
           map.dev,
           map.func);

  FILE *fp = fopen(path, "rb");
  if (!fp) {
    fprintf(stderr, "Warning: cannot open %s to check bus mastering.\n", path);
    return;
  }

  // PCI COMMAND register: offset 0x4, bit 2 is Bus Master Enable.
  uint16_t command = 0;
  bool read_ok =
      (fseek(fp, 0x4, SEEK_SET) == 0) && (fread(&command, 2, 1, fp) == 1);
  fclose(fp);

  if (!read_ok) {
    fprintf(stderr, "Warning: cannot read PCI COMMAND from %s.\n", path);
    return;
  }

  if (!(command & 0x4)) {
    fprintf(stderr,
            "Bus mastering is disabled on %04x:%02x:%02x.%d, so the FPGA "
            "cannot write to host memory and every stream would stall.\n"
            "Enable it with:\n"
            "    sudo setpci -s %04x:%02x:%02x.%d 4.w=6\n",
            map.domain,
            map.bus,
            map.dev,
            map.func,
            map.domain,
            map.bus,
            map.dev,
            map.func);
    fpga_shutdown();
    exit(1);
  }
}

simif_f2_t::~simif_f2_t() {
  for (auto &buffer : dma_buffers) {
    uint64_t va = buffer.first;
    fpga_dma_mem_unmap(&va, buffer.second);
  }
  fpga_shutdown();
}

void simif_f2_t::write(size_t addr, uint32_t data) {
  // fprintf(stderr, "OCL write addr=0x%08lx <- value=0x%08x\n", addr, data); // rh: log OCL writes
  int rc = fpga_pci_poke(pci_bar_handle, addr, data);
  check_rc(rc, "OCL write FAILED");
}

uint32_t simif_f2_t::read(size_t addr) {
  uint32_t value;
  int rc = fpga_pci_peek(pci_bar_handle, addr, &value);
  check_rc(rc, "OCL read FAILED");
  // fprintf(stderr, "OCL read  addr=0x%08lx -> value=0x%08x\n", addr, value); // rh: log OCL reads
  return value & 0xFFFFFFFF;
}

// rh: replace XDMA with 32b reads over the 512b beat
size_t simif_f2_t::cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
  // fprintf(stderr, "PCIS read:  addr=0x%lx size=%zu\n", addr, size); // rh: log PCIS reads
  size_t bytes_read = 0;
  uint32_t *data32 = (uint32_t *)data;
  size_t num_words = size / 4; // rh: should always be byte aligned since FPGAToCPUDriver has an assert
  
  for (size_t i = 0; i < num_words; i++) {
    int rc = fpga_pci_peek(pci_bar4_handle, addr + (i * 4), &data32[i]);
    check_rc(rc, "PCIS read FAILED");
    bytes_read += 4;
  }
  
  // fprintf(stderr, "PCIS read:  addr=0x%lx size=%zu SUCCESS (read %zu bytes)\n", addr, size, bytes_read);
  return bytes_read;
}

// rh: replace XDMA with burst
size_t simif_f2_t::cpu_managed_axi4_write(size_t addr, const char *data, size_t size) {
  // fprintf(stderr, "PCIS write: addr=0x%lx size=%zu\n", addr, size); //rh: log PCIS writes
  int rc = fpga_pci_write_burst(pci_bar4_handle, addr, (uint32_t *) data, size / 4);
  check_rc(rc, "PCIS write FAILED");
  // fprintf(stderr, "PCIS write: addr=0x%lx size=%zu SUCCESS\n", addr, size);
  return size;
}

uint32_t simif_f2_t::is_write_ready() {
  uint64_t addr = 0x4;
  uint32_t value;
  int rc = fpga_pci_peek(pci_bar_handle, addr, &value);
  check_rc(rc, NULL);
  return value & 0xFFFFFFFF;
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_f2_t>(config, args);
}
