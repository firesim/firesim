#include "simif_f1.h"
#include <cassert>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

simif_f1_t::simif_f1_t(const std::vector<std::string> &args) : simif_t(args) {
#ifdef SIMULATION_XSIM
  mkfifo(driver_to_xsim, 0666);
  fprintf(stderr, "opening driver to xsim\n");
  driver_to_xsim_fd = open(driver_to_xsim, O_WRONLY);
  fprintf(stderr, "opening xsim to driver\n");
  xsim_to_driver_fd = open(xsim_to_driver, O_RDONLY);
#else
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
#endif

  using namespace std::placeholders;
  auto mmio_read_func = std::bind(&simif_f1_t::read, this, _1);
  auto cpu_managed_axi4_read_func =
      std::bind(&simif_f1_t::cpu_managed_axi4_read, this, _1, _2, _3);
  auto cpu_managed_axi4_write_func =
      std::bind(&simif_f1_t::cpu_managed_axi4_write, this, _1, _2, _3);

  for (int i = 0; i < CPUMANAGEDSTREAMENGINE_0_from_cpu_stream_count; i++) {
    auto params = CPUManagedStreamParameters(
        std::string(CPUMANAGEDSTREAMENGINE_0_from_cpu_names[i]),
        CPUMANAGEDSTREAMENGINE_0_from_cpu_dma_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_from_cpu_count_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_from_cpu_buffer_sizes[i]);

    from_host_streams.push_back(
        StreamFromCPU(params, mmio_read_func, cpu_managed_axi4_write_func));
  }

  for (int i = 0; i < CPUMANAGEDSTREAMENGINE_0_to_cpu_stream_count; i++) {
    auto params = CPUManagedStreamParameters(
        std::string(CPUMANAGEDSTREAMENGINE_0_to_cpu_names[i]),
        CPUMANAGEDSTREAMENGINE_0_to_cpu_dma_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_to_cpu_count_addrs[i],
        CPUMANAGEDSTREAMENGINE_0_to_cpu_buffer_sizes[i]);

    to_host_streams.push_back(
        StreamToCPU(params, mmio_read_func, cpu_managed_axi4_read_func));
  }
}

size_t simif_f1_t::pull(unsigned stream_idx,
                        void *dest,
                        size_t num_bytes,
                        size_t threshold_bytes) {
  assert(stream_idx < to_host_streams.size());
  return this->to_host_streams[stream_idx].pull(
      dest, num_bytes, threshold_bytes);
}

size_t simif_f1_t::push(unsigned stream_idx,
                        void *src,
                        size_t num_bytes,
                        size_t threshold_bytes) {
  assert(stream_idx < from_host_streams.size());
  return this->from_host_streams[stream_idx].push(
      src, num_bytes, threshold_bytes);
}

void simif_f1_t::check_rc(int rc, char *infostr) {
#ifndef SIMULATION_XSIM
  if (rc) {
    if (infostr) {
      fprintf(stderr, "%s\n", infostr);
    }
    fprintf(stderr, "INVALID RETCODE: %d\n", rc, infostr);
    fpga_shutdown();
    exit(1);
  }
#endif
}

void simif_f1_t::fpga_shutdown() {
#ifndef SIMULATION_XSIM
  int rc = fpga_pci_detach(pci_bar_handle);
  // don't call check_rc because of fpga_shutdown call. do it manually:
  if (rc) {
    fprintf(stderr, "Failure while detaching from the fpga: %d\n", rc);
  }
  close(edma_write_fd);
  close(edma_read_fd);
#endif
}

void simif_f1_t::fpga_setup(int slot_id) {
#ifndef SIMULATION_XSIM
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
#endif
}

simif_f1_t::~simif_f1_t() { fpga_shutdown(); }

void simif_f1_t::write(size_t addr, uint32_t data) {
#ifdef SIMULATION_XSIM
  uint64_t cmd = (((uint64_t)(0x80000000 | addr)) << 32) | (uint64_t)data;
  char *buf = (char *)&cmd;
  ::write(driver_to_xsim_fd, buf, 8);
#else
  int rc = fpga_pci_poke(pci_bar_handle, addr, data);
  check_rc(rc, NULL);
#endif
}

uint32_t simif_f1_t::read(size_t addr) {
#ifdef SIMULATION_XSIM
  uint64_t cmd = addr;
  char *buf = (char *)&cmd;
  ::write(driver_to_xsim_fd, buf, 8);

  int gotdata = 0;
  while (gotdata == 0) {
    gotdata = ::read(xsim_to_driver_fd, buf, 8);
    if (gotdata != 0 && gotdata != 8) {
      printf("ERR GOTDATA %d\n", gotdata);
    }
  }
  return *((uint64_t *)buf);
#else
  uint32_t value;
  int rc = fpga_pci_peek(pci_bar_handle, addr, &value);
  return value & 0xFFFFFFFF;
#endif
}

size_t simif_f1_t::cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
#ifdef SIMULATION_XSIM
  assert(false); // PCIS is unsupported in FPGA-level metasimulation
#else
  return ::pread(edma_read_fd, data, size, addr);
#endif
}

size_t
simif_f1_t::cpu_managed_axi4_write(size_t addr, char *data, size_t size) {
#ifdef SIMULATION_XSIM
  assert(false); // PCIS is unsupported in FPGA-level metasimulation
#else
  return ::pwrite(edma_write_fd, data, size, addr);
#endif
}

uint32_t simif_f1_t::is_write_ready() {
  uint64_t addr = 0x4;
#ifdef SIMULATION_XSIM
  uint64_t cmd = addr;
  char *buf = (char *)&cmd;
  ::write(driver_to_xsim_fd, buf, 8);

  int gotdata = 0;
  while (gotdata == 0) {
    gotdata = ::read(xsim_to_driver_fd, buf, 8);
    if (gotdata != 0 && gotdata != 8) {
      printf("ERR GOTDATA %d\n", gotdata);
    }
  }
  return *((uint64_t *)buf);
#else
  uint32_t value;
  int rc = fpga_pci_peek(pci_bar_handle, addr, &value);
  check_rc(rc, NULL);
  return value & 0xFFFFFFFF;
#endif
}

int main(int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return simif_f1_t(args).run();
}
