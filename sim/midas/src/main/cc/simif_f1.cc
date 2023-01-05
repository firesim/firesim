#include <cassert>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/cpu_managed_stream.h"
#include "core/simif.h"

#include <fpga_mgmt.h>
#include <fpga_pci.h>

class simif_f1_t final : public simif_t, public CPUManagedStreamIO {
public:
  simif_f1_t(const TargetConfig &config, const std::vector<std::string> &args);
  ~simif_f1_t();

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  uint32_t is_write_ready();
  void check_rc(int rc, char *infostr);
  void fpga_shutdown();
  void fpga_setup(int slot_id);

  CPUManagedStreamIO &get_cpu_managed_stream_io() override { return *this; }

private:
  uint32_t mmio_read(size_t addr) override { return read(addr); }
  size_t
  cpu_managed_axi4_write(size_t addr, const char *data, size_t size) override;
  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size) override;
  uint64_t get_beat_bytes() const override {
    return config.cpu_managed->beat_bytes();
  }

  //    int rc;
  int slot_id;
  int edma_write_fd;
  int edma_read_fd;
  pci_bar_handle_t pci_bar_handle;
};

simif_f1_t::simif_f1_t(const TargetConfig &config,
                       const std::vector<std::string> &args)
    : simif_t(config, args) {
  slot_id = -1;

  for (auto &arg : args) {
    if (arg.find("+slotid=") == 0) {
      slot_id = atoi((arg.c_str()) + 8);
    }
  }
  if (slot_id == -1) {
    fprintf(stderr, "Slot ID not specified. Assuming Slot 0\n");
    slot_id = 0;
  }
  fpga_setup(slot_id);
}

void simif_f1_t::check_rc(int rc, char *infostr) {
  if (rc) {
    if (infostr) {
      fprintf(stderr, "%s\n", infostr);
    }
    fprintf(stderr, "INVALID RETCODE: %d\n", rc, infostr);
    fpga_shutdown();
    exit(1);
  }
}

void simif_f1_t::fpga_shutdown() {
  int rc = fpga_pci_detach(pci_bar_handle);
  // don't call check_rc because of fpga_shutdown call. do it manually:
  if (rc) {
    fprintf(stderr, "Failure while detaching from the fpga: %d\n", rc);
  }
  close(edma_write_fd);
  close(edma_read_fd);
}

void simif_f1_t::fpga_setup(int slot_id) {
  /*
   * pci_vendor_id and pci_device_id values below are Amazon's and available
   * to use for a given FPGA slot.
   * Users may replace these with their own if allocated to them by PCI SIG
   */
  uint16_t pci_vendor_id = 0x1D0F; /* Amazon PCI Vendor ID */
  uint16_t pci_device_id =
      0xF000; /* PCI Device ID preassigned by Amazon for F1 applications */

  int rc = fpga_mgmt_init();
  check_rc(rc, "fpga_mgmt_init FAILED");

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

  /* attach to BAR0 */
  pci_bar_handle = PCI_BAR_HANDLE_INIT;
  rc = fpga_pci_attach(slot_id, FPGA_APP_PF, APP_PF_BAR0, 0, &pci_bar_handle);
  check_rc(rc, "fpga_pci_attach FAILED");

  // EDMA setup
  char device_file_name[256];
  char device_file_name2[256];

  sprintf(device_file_name, "/dev/xdma%d_h2c_0", slot_id);
  printf("Using xdma write queue: %s\n", device_file_name);
  sprintf(device_file_name2, "/dev/xdma%d_c2h_0", slot_id);
  printf("Using xdma read queue: %s\n", device_file_name2);

  edma_write_fd = open(device_file_name, O_WRONLY);
  edma_read_fd = open(device_file_name2, O_RDONLY);
  assert(edma_write_fd >= 0);
  assert(edma_read_fd >= 0);
}

simif_f1_t::~simif_f1_t() { fpga_shutdown(); }

void simif_f1_t::write(size_t addr, uint32_t data) {
  int rc = fpga_pci_poke(pci_bar_handle, addr, data);
  check_rc(rc, NULL);
}

uint32_t simif_f1_t::read(size_t addr) {
  uint32_t value;
  int rc = fpga_pci_peek(pci_bar_handle, addr, &value);
  return value & 0xFFFFFFFF;
}

size_t simif_f1_t::cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
  return ::pread(edma_read_fd, data, size, addr);
}

size_t
simif_f1_t::cpu_managed_axi4_write(size_t addr, const char *data, size_t size) {
  return ::pwrite(edma_write_fd, data, size, addr);
}

uint32_t simif_f1_t::is_write_ready() {
  uint64_t addr = 0x4;
  uint32_t value;
  int rc = fpga_pci_peek(pci_bar_handle, addr, &value);
  check_rc(rc, NULL);
  return value & 0xFFFFFFFF;
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_f1_t>(config, args);
}
