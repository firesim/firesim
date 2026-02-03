#include <cassert>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/cpu_managed_stream.h"
#include "bridges/fpga_managed_stream.h"
#include "core/simif.h"

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
  void fpga_setup(int slot_id, const std::string &agfi, bool debug_enabled);
  void print_debug_status();
  void wait_for_ddr_ready();

  CPUManagedStreamIO &get_cpu_managed_stream_io() override { return *this; }
  FPGAManagedStreamIO &get_fpga_managed_stream_io() override { return *this; }

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
  char *get_memory_base() override { return NULL; }

  // int edma_write_fd; // rh: i'm leaving this in as a reminder that the beta starts soon and all this work will be for nothing
  // int edma_read_fd;
  pci_bar_handle_t pci_bar_handle;
  pci_bar_handle_t pci_bar4_handle;
  bool debug_enabled;
};

simif_f2_t::simif_f2_t(const TargetConfig &config,
                       const std::vector<std::string> &args)
    : simif_t(config) {

  int slot_id = -1;
  std::string agfi;
  bool debug = false;
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
    if (arg.find("+debug") == 0) {
      debug = true;
      continue;
    }
  }

  if (slot_id == -1) {
    fprintf(stderr, "Slot ID not specified. Assuming Slot 0\n");
    slot_id = 0;
  }

  fpga_setup(slot_id, agfi, debug);
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

void simif_f2_t::fpga_setup(int slot_id, const std::string &agfi, bool debug) {
  debug_enabled = debug;
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

  if (debug_enabled) {
    print_debug_status();
  }
  
  wait_for_ddr_ready();

  /* NOTE: SDA interface is on MgmtPF BAR4, not AppPF BAR2 accessible via standard driver.
   * The RTL has been fixed to use gen_clk_main_a0 which has gen_rst_main_n released by default,
   * so no SDA access is needed.
   */
}

/**
 * Print hardware debug registers (clock/reset status, DDR status).
 * Only called when +debug is specified.
 */
void simif_f2_t::print_debug_status() {
  int rc;
  printf("\n=== HARDWARE DEBUG STATUS ===\n");
  
  uint32_t debug_status = 0;
  uint32_t debug_counter = 0;
  uint32_t debug_raw = 0;
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00000, &debug_status);
  if (rc == 0) {
    printf("DEBUG[0x03F00000] status    = 0x%08x\n", debug_status);
    printf("  Bit 0 (firesim_clocking_locked): %d\n", (debug_status >> 0) & 1);
    printf("  Bit 1 (rst_main_n):              %d\n", (debug_status >> 1) & 1);
    printf("  Bit 2 (rst_main_n_sync):         %d\n", (debug_status >> 2) & 1);
    printf("  Bit 3 (rst_firesim_n_sync):      %d\n", (debug_status >> 3) & 1);
    printf("  Bit 4 (combined_firesim_rst_n):  %d\n", (debug_status >> 4) & 1);
    printf("  Bit 5 (ddr_ready_sync):          %d\n", (debug_status >> 5) & 1);
    printf("  Bits 31:16 (marker):             0x%04x (expect 0xDEAD)\n", (debug_status >> 16) & 0xFFFF);
  } else {
    printf("DEBUG[0x03F00000] read FAILED (rc=%d) - debug registers may not be in bitstream\n", rc);
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00004, &debug_counter);
  if (rc == 0) {
    printf("DEBUG[0x03F00004] clk_counter = 0x%08x (%u cycles)\n", debug_counter, debug_counter);
    // Read again to see if counter is advancing
    uint32_t debug_counter2 = 0;
    rc = fpga_pci_peek(pci_bar_handle, 0x03F00004, &debug_counter2);
    if (rc == 0) {
      printf("DEBUG[0x03F00004] clk_counter = 0x%08x (2nd read, delta=%d)\n", 
             debug_counter2, (int)(debug_counter2 - debug_counter));
      if (debug_counter2 == debug_counter) {
        printf("WARNING: Clock counter not advancing - firesim_internal_clock may be stuck!\n");
      }
    }
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00008, &debug_raw);
  if (rc == 0) {
    printf("DEBUG[0x03F00008] raw_bits   = 0x%08x\n", debug_raw);
  }
  
  /* DDR Debug Registers */
  uint32_t ddr_status = 0;
  uint32_t ddr_aw_count = 0;
  uint32_t ddr_ar_count = 0;
  uint32_t ddr_b_count = 0;
  uint32_t ddr_r_count = 0;
  uint32_t ddr_slave0_status = 0;
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00020, &ddr_status);
  if (rc == 0) {
    printf("DEBUG[0x03F00020] DDR_status = 0x%08x\n", ddr_status);
    printf("  Bit 0 (ddr_ready):      %d\n", (ddr_status >> 0) & 1);
    printf("  Bit 1 (aw_pending):     %d\n", (ddr_status >> 1) & 1);
    printf("  Bit 2 (ar_pending):     %d\n", (ddr_status >> 2) & 1);
    printf("  Bit 3 (awvalid):        %d\n", (ddr_status >> 3) & 1);
    printf("  Bit 4 (wvalid):         %d\n", (ddr_status >> 4) & 1);
    printf("  Bit 5 (bvalid):         %d\n", (ddr_status >> 5) & 1);
    printf("  Bit 6 (arvalid):        %d\n", (ddr_status >> 6) & 1);
    printf("  Bit 7 (rvalid):         %d\n", (ddr_status >> 7) & 1);
    printf("  Bits 31:16 (marker):    0x%04x (expect 0xDDDD)\n", (ddr_status >> 16) & 0xFFFF);
  } else {
    printf("DEBUG[0x03F00020] DDR_status read FAILED (rc=%d) - DDR debug may not be in bitstream\n", rc);
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00024, &ddr_aw_count);
  if (rc == 0) {
    printf("DEBUG[0x03F00024] DDR_AW_cnt = %u\n", ddr_aw_count);
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00028, &ddr_ar_count);
  if (rc == 0) {
    printf("DEBUG[0x03F00028] DDR_AR_cnt = %u\n", ddr_ar_count);
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F0002C, &ddr_b_count);
  if (rc == 0) {
    printf("DEBUG[0x03F0002C] DDR_B_cnt  = %u\n", ddr_b_count);
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00030, &ddr_r_count);
  if (rc == 0) {
    printf("DEBUG[0x03F00030] DDR_R_cnt  = %u\n", ddr_r_count);
  }
  
  rc = fpga_pci_peek(pci_bar_handle, 0x03F00034, &ddr_slave0_status);
  if (rc == 0) {
    printf("DEBUG[0x03F00034] slave0_st  = 0x%08x\n", ddr_slave0_status);
    printf("  Bit 0 (awready):    %d\n", (ddr_slave0_status >> 0) & 1);
    printf("  Bit 1 (arready):    %d\n", (ddr_slave0_status >> 1) & 1);
    printf("  Bit 2 (rready):     %d\n", (ddr_slave0_status >> 2) & 1);
    printf("  Bit 3 (awvalid):    %d\n", (ddr_slave0_status >> 3) & 1);
    printf("  Bit 4 (wvalid):     %d\n", (ddr_slave0_status >> 4) & 1);
    printf("  Bit 5 (bvalid):     %d\n", (ddr_slave0_status >> 5) & 1);
    printf("  Bit 6 (arvalid):    %d\n", (ddr_slave0_status >> 6) & 1);
    printf("  Bit 7 (rvalid):     %d\n", (ddr_slave0_status >> 7) & 1);
    printf("  Bits 31:16 (marker): 0x%04x (expect 0xF1F1)\n", (ddr_slave0_status >> 16) & 0xFFFF);
  }

  printf("=== END DEBUG STATUS ===\n\n");
}

// rh: wait for the ddr_ready signal to be asserted to start sending, else deadlock (?)
void simif_f2_t::wait_for_ddr_ready() {
  int rc;
  uint32_t ddr_status = 0;

  if (debug_enabled) {
    printf("Waiting for DDR ready...\n");
  }

  int ddr_wait_attempts = 0;
  const int MAX_DDR_WAIT_MS = 5000;
  while (ddr_wait_attempts < MAX_DDR_WAIT_MS) {
    rc = fpga_pci_peek(pci_bar_handle, 0x03F00020, &ddr_status);
    if (rc == 0 && (ddr_status & 0x1)) {  // Bit 0 is ddr_ready
      if (debug_enabled) {
        printf("DDR ready after %d ms (status=0x%08x)\n", ddr_wait_attempts, ddr_status);
      }
      return;
    }
    usleep(1000);
    ddr_wait_attempts++;
  }

  fprintf(stderr, "WARNING: DDR not ready after %d ms timeout (status=0x%08x)\n",
          MAX_DDR_WAIT_MS, ddr_status);
  fprintf(stderr, "         Continuing anyway - RTL reset gate should prevent issues\n");
}

simif_f2_t::~simif_f2_t() { fpga_shutdown(); }

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