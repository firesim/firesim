#include <cassert>

#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/cpu_managed_stream.h"
#include "core/simif.h"

#define PCI_DEV_FMT "%04x:%02x:%02x.%d"

class simif_xilinx_alveo_u250_t final : public simif_t,
                                        public CPUManagedStreamIO {
public:
  simif_xilinx_alveo_u250_t(const TargetConfig &config,
                            const std::vector<std::string> &args);
  ~simif_xilinx_alveo_u250_t();

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

  void *fpga_pci_bar_get_mem_at_offset(uint64_t offset);
  int fpga_pci_poke(uint64_t offset, uint32_t value);
  int fpga_pci_peek(uint64_t offset, uint32_t *value);

  int edma_write_fd;
  int edma_read_fd;
  void *bar0_base;
  uint32_t bar0_size = 0x2000000; // 32 MB
};

static int fpga_pci_check_file_id(char *path, uint16_t id) {
  assert(path);
  int ret = 0;
  FILE *fp = fopen(path, "r");
  assert(fp);
  uint32_t tmp_id;
  ret = fscanf(fp, "%x", &tmp_id);
  assert(ret >= 0);
  assert(tmp_id == id);
  fclose(fp);
  return 0;
}

simif_xilinx_alveo_u250_t::simif_xilinx_alveo_u250_t(
    const TargetConfig &config, const std::vector<std::string> &args)
    : simif_t(config) {

  int slot_id = -1;
  for (auto &arg : args) {
    if (arg.find("+slotid=") == 0) {
      slot_id = atoi((arg.c_str()) + 8);
      continue;
    }
  }

  if (slot_id == -1) {
    fprintf(stderr, "Slot ID not specified. Assuming Slot 0\n");
    slot_id = 0;
  }

  // note: slot_id here corresponds to the ID in the BDF (i.e. 0000:<THIS>:00.0)
  fpga_setup(slot_id);
}

void *
simif_xilinx_alveo_u250_t::fpga_pci_bar_get_mem_at_offset(uint64_t offset) {
  assert(!(((uint64_t)(offset + 4)) > bar0_size));
  return (uint8_t *)bar0_base + offset;
}

int simif_xilinx_alveo_u250_t::fpga_pci_poke(uint64_t offset, uint32_t value) {
  uint32_t *reg_ptr = (uint32_t *)fpga_pci_bar_get_mem_at_offset(offset);
  *reg_ptr = value;
  return 0;
}

int simif_xilinx_alveo_u250_t::fpga_pci_peek(uint64_t offset, uint32_t *value) {
  uint32_t *reg_ptr = (uint32_t *)fpga_pci_bar_get_mem_at_offset(offset);
  *value = *reg_ptr;
  return 0;
}

void simif_xilinx_alveo_u250_t::check_rc(int rc, char *infostr) {
  if (rc) {
    if (infostr) {
      fprintf(stderr, "%s\n", infostr);
    }
    fprintf(stderr, "INVALID RETCODE: %d\n", rc);
    fpga_shutdown();
    exit(1);
  }
}

void simif_xilinx_alveo_u250_t::fpga_shutdown() {
  int ret = munmap(bar0_base, bar0_size);
  assert(ret == 0);
  close(edma_write_fd);
  close(edma_read_fd);
}

/**
 * Xilinx PCI Vendor ID.
 */
constexpr uint16_t pci_vendor_id = 0x10ee;

/**
 * Xilinx PCI Device ID pre-assigned by for XDMA applications.
 */
constexpr uint16_t pci_device_id = 0x903f;

void simif_xilinx_alveo_u250_t::fpga_setup(int slot_id) {
  int domain = 0;
  int device_id = 0;
  int pf_id = 0;
  int bar_id = 0;

  int fd = -1;
  char sysfs_name[256];
  int ret;

  // check vendor id
  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/" PCI_DEV_FMT "/vendor",
                 domain,
                 slot_id,
                 device_id,
                 pf_id);
  assert(ret >= 0);
  fpga_pci_check_file_id(sysfs_name, pci_vendor_id);

  // check device id
  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/" PCI_DEV_FMT "/device",
                 domain,
                 slot_id,
                 device_id,
                 pf_id);
  assert(ret >= 0);
  fpga_pci_check_file_id(sysfs_name, pci_device_id);

  // open and memory map
  snprintf(sysfs_name,
           sizeof(sysfs_name),
           "/sys/bus/pci/devices/" PCI_DEV_FMT "/resource%u",
           domain,
           slot_id,
           device_id,
           pf_id,
           bar_id);

  fd = open(sysfs_name, O_RDWR | O_SYNC);
  assert(fd != -1);

  bar0_base = mmap(0, bar0_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  assert(bar0_base != MAP_FAILED);
  close(fd);
  fd = -1;

  // XDMA setup
  char device_file_name[256];
  char device_file_name2[256];

  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/" PCI_DEV_FMT "/xdma",
                 domain,
                 slot_id,
                 device_id,
                 pf_id);
  assert(ret >= 0);
  DIR *d;
  struct dirent *dir;
  int xdma_id = -1;

  d = opendir(sysfs_name);
  if (d) {
    while ((dir = readdir(d)) != NULL) {
      printf("examining xdma/%s\n", dir->d_name);
      if (strstr(dir->d_name, "xdma")) {
        xdma_id = strtol(dir->d_name + 4, NULL, 10);
        break;
      }
    }
    closedir(d);
  }

  assert(xdma_id != -1);

  sprintf(device_file_name, "/dev/xdma%d_h2c_0", xdma_id);
  printf("Using xdma write queue: %s\n", device_file_name);
  sprintf(device_file_name2, "/dev/xdma%d_c2h_0", xdma_id);
  printf("Using xdma read queue: %s\n", device_file_name2);

  edma_write_fd = open(device_file_name, O_WRONLY);
  edma_read_fd = open(device_file_name2, O_RDONLY);
  assert(edma_write_fd >= 0);
  assert(edma_read_fd >= 0);
}

simif_xilinx_alveo_u250_t::~simif_xilinx_alveo_u250_t() { fpga_shutdown(); }

void simif_xilinx_alveo_u250_t::write(size_t addr, uint32_t data) {
  int rc = fpga_pci_poke(addr, data);
  check_rc(rc, NULL);
}

uint32_t simif_xilinx_alveo_u250_t::read(size_t addr) {
  uint32_t value;
  int rc = fpga_pci_peek(addr, &value);
  return value & 0xFFFFFFFF;
}

size_t simif_xilinx_alveo_u250_t::cpu_managed_axi4_read(size_t addr,
                                                        char *data,
                                                        size_t size) {
  return ::pread(edma_read_fd, data, size, addr);
}

size_t simif_xilinx_alveo_u250_t::cpu_managed_axi4_write(size_t addr,
                                                         const char *data,
                                                         size_t size) {
  return ::pwrite(edma_write_fd, data, size, addr);
}

uint32_t simif_xilinx_alveo_u250_t::is_write_ready() {
  uint64_t addr = 0x4;
  uint32_t value;
  int rc = fpga_pci_peek(addr, &value);
  check_rc(rc, NULL);
  return value & 0xFFFFFFFF;
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_xilinx_alveo_u250_t>(config, args);
}
