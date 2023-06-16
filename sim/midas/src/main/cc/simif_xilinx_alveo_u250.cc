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
  void fpga_setup(uint16_t domain_id,
                  uint8_t bus_id,
                  uint8_t device_id,
                  uint8_t pf_id,
                  uint8_t bar_id,
                  uint16_t pci_vendor_id,
                  uint16_t pci_device_id);

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
  uint32_t bar0_size = 0x2000000; // 32 MB (TODO: Make configurable?)
};

static int fpga_pci_check_file_id(char *path, uint16_t id) {
  if (path) {
    fprintf(stdout, "Opening %s\n", path);
  } else {
    assert(path);
  }
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

  std::optional<uint16_t> domain_id;
  std::optional<uint8_t> bus_id;
  std::optional<uint8_t> device_id;
  std::optional<uint8_t> pf_id;
  std::optional<uint8_t> bar_id;
  std::optional<uint16_t> pci_vendor_id;
  std::optional<uint16_t> pci_device_id;

  for (auto &arg : args) {
    if (arg.find("+domain=") == 0) {
      domain_id = strtoul(arg.c_str() + 8, NULL, 16);
      continue;
    }
    if (arg.find("+bus=") == 0) {
      bus_id = strtoul(arg.c_str() + 5, NULL, 16);
      continue;
    }
    if (arg.find("+device=") == 0) {
      device_id = strtoul(arg.c_str() + 8, NULL, 16);
      continue;
    }
    if (arg.find("+function=") == 0) {
      pf_id = strtoul(arg.c_str() + 10, NULL, 16);
      continue;
    }
    if (arg.find("+bar=") == 0) {
      bar_id = strtoul(arg.c_str() + 5, NULL, 16);
      continue;
    }
    if (arg.find("+pci-vendor=") == 0) {
      pci_vendor_id = strtoul(arg.c_str() + 12, NULL, 16);
      continue;
    }
    if (arg.find("+pci-device=") == 0) {
      pci_device_id = strtoul(arg.c_str() + 12, NULL, 16);
      continue;
    }
  }

  if (!domain_id) {
    fprintf(stderr, "Domain ID not specified. Assuming Domain ID 0\n");
    domain_id = 0;
  }
  if (!bus_id) {
    fprintf(stderr, "Bus ID not specified. Assuming Bus ID 0\n");
    bus_id = 0;
  }
  if (!device_id) {
    fprintf(stderr, "Device ID not specified. Assuming Device ID 0\n");
    device_id = 0;
  }
  if (!pf_id) {
    fprintf(stderr, "Function ID not specified. Assuming Function ID 0\n");
    pf_id = 0;
  }
  if (!bar_id) {
    fprintf(stderr, "BAR ID not specified. Assuming BAR ID 0\n");
    bar_id = 0;
  }
  if (!pci_vendor_id) {
    fprintf(stderr,
            "PCI Vendor ID not specified. Assuming PCI Vendor ID 0x10ee\n");
    pci_vendor_id = 0x10ee;
  }
  if (!pci_device_id) {
    fprintf(stderr,
            "PCI Device ID not specified. Assuming PCI Device ID 0x903f\n");
    pci_device_id = 0x903f;
  }

  printf("Using: " PCI_DEV_FMT
         ", BAR ID: %u, PCI Vendor ID: 0x%04x, PCI Device ID: 0x%04x\n",
         *domain_id,
         *bus_id,
         *device_id,
         *pf_id,
         *bar_id,
         *pci_vendor_id,
         *pci_device_id);

  fpga_setup(*domain_id,
             *bus_id,
             *device_id,
             *pf_id,
             *bar_id,
             *pci_vendor_id,
             *pci_device_id);
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
  if (bar0_base) {
    int ret = munmap(bar0_base, bar0_size);
    assert(ret == 0);
  }
  close(edma_write_fd);
  close(edma_read_fd);
}

void simif_xilinx_alveo_u250_t::fpga_setup(uint16_t domain_id,
                                           uint8_t bus_id,
                                           uint8_t device_id,
                                           uint8_t pf_id,
                                           uint8_t bar_id,
                                           uint16_t pci_vendor_id,
                                           uint16_t pci_device_id) {

  int fd = -1;
  char sysfs_name[256];
  int ret;

  // check vendor id
  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/" PCI_DEV_FMT "/vendor",
                 domain_id,
                 bus_id,
                 device_id,
                 pf_id);
  assert(ret >= 0);
  fpga_pci_check_file_id(sysfs_name, pci_vendor_id);

  // check device id
  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/" PCI_DEV_FMT "/device",
                 domain_id,
                 bus_id,
                 device_id,
                 pf_id);
  assert(ret >= 0);
  fpga_pci_check_file_id(sysfs_name, pci_device_id);

  // XDMA setup
  char device_file_name[256];
  char device_file_name2[256];
  char user_file_name[256];

  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/" PCI_DEV_FMT "/xdma",
                 domain_id,
                 bus_id,
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
      if (strstr(dir->d_name, "xdma") && strstr(dir->d_name, "_h2c_0")) {
        xdma_id = strtol(dir->d_name + 4, NULL, 10);
        break;
      }
    }
    closedir(d);
  }

  assert(xdma_id != -1);

  // open and memory map
  sprintf(user_file_name, "/dev/xdma%d_user", xdma_id);

  fd = open(user_file_name, O_RDWR | O_SYNC);
  assert(fd != -1);

  bar0_base = mmap(0, bar0_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  assert(bar0_base != MAP_FAILED);
  close(fd);
  fd = -1;

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
