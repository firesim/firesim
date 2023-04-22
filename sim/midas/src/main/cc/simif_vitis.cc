#include <cassert>

#include <fcntl.h>
#include <iostream>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/fpga_managed_stream.h"
#include "core/simif.h"

#include "experimental/xrt_device.h"
#include "experimental/xrt_ip.h"
#include "experimental/xrt_kernel.h"

class simif_vitis_t final : public simif_t, public FPGAManagedStreamIO {
public:
  simif_vitis_t(const TargetConfig &config,
                const std::vector<std::string> &args);
  ~simif_vitis_t() {}

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  uint32_t is_write_ready();

  FPGAManagedStreamIO &get_fpga_managed_stream_io() override { return *this; }

private:
  uint32_t mmio_read(size_t addr) override { return read(addr); }

  void mmio_write(size_t addr, uint32_t value) override {
    return write(addr, value);
  }

  char *get_memory_base() override;

private:
  int slotid;
  std::string binary_file;
  xrt::device device_handle;
  xrt::uuid uuid;
  xrt::ip kernel_handle;
  xrt::run run_handle;
  char* host_mem_0_map;
  xrt::bo host_mem_0;
};

constexpr size_t u250_dram_channel_size_bytes = 16ULL * 1024 * 1024 * 1024;
/**
 * We currently support only a single FPGA DRAM channel. When the buffer is
 * allocated in XRT, we'd generally have to write the offset back to the kernel
 * using MMIO. For now we've hardcoded the offset for a U250 with no other
 * concurrently running kernel.
 */
constexpr uint64_t u250_dram_expected_offset = 0x4000000000L;

simif_vitis_t::simif_vitis_t(const TargetConfig &config,
                             const std::vector<std::string> &args)
    : simif_t(config) {
  slotid = -1;
  binary_file = "";

  // TODO: Properly read out arguments
  for (auto &arg : args) {
    if (arg.find("+slotid=") == 0) {
      slotid = atoi((arg.c_str()) + 8);
    }
    if (arg.find("+binary_file=") == 0) {
      binary_file = arg.substr(
          13, std::string::npos); //"kernel.xclbin"; //atoi((arg.c_str()) + 8);
    }
  }

  if (slotid == -1) {
    fprintf(stderr, "Device ID not specified. Assuming Device 0.\n");
    slotid = 0;
  }

  if (binary_file == "") {
    fprintf(stderr, "No binary file specified.\n");
    exit(1);
  }

  fprintf(stdout, "DEBUG: DevIdx:%d XCLBin:%s\n", slotid, binary_file.c_str());

  // Open the FPGA device
  device_handle = xrt::device(slotid);

  // Load the XCLBIN (get handle, then load)
  uuid = device_handle.load_xclbin(binary_file);

  // Open Kernel
  kernel_handle = xrt::ip(device_handle, uuid, "firesim");

  // Get kernel metadata
  auto xclbin = xrt::xclbin(binary_file);

  // Only 1 MEM_DDR4 region so use that offset
  // determine the bank index for the dram channel.
  // this is an adaptation of https://xilinx.github.io/XRT/master/html/xclbintools.html
  int32_t fpga_mem_0_idx = -1;
  for (auto& mem : xclbin.get_mems()) {
    std::cout << "DEBUG: mems Tag:" << mem.get_tag() << " Used:" << mem.get_used() << " Idx:" << mem.get_index() << std::hex << " BAddr:" << mem.get_base_address() << std::dec << " SizeKB:" << mem.get_size_kb() << std::endl;
    if (mem.get_used() && mem.get_type() == xrt::xclbin::mem::memory_type::ddr4) {
      if (fpga_mem_0_idx == -1) {
        fpga_mem_0_idx = mem.get_index();
      } else {
        // only supporting 1 DRAM port
        std::cerr
            << "Found more DRAM ports than we can deal with"
            << std::endl;
        exit(1);
      }
    }
  }

  if (fpga_mem_0_idx == -1) {
    std::cerr
        << "Unable to find idx of dram port"
        << std::endl;
    exit(1);
  }

  // Intialize FPGA-DRAM regions.
  auto fpga_mem_0 = xrt::bo(device_handle,
                            u250_dram_channel_size_bytes,
                            xrt::bo::flags::device_only,
                            fpga_mem_0_idx);

  if (fpga_mem_0.address() != u250_dram_expected_offset) {
    std::cerr
        << "Allocated device_only buffer address not match the expected offset."
        << std::endl;
    std::cerr << "Expected: " << u250_dram_expected_offset
              << "Received: " << fpga_mem_0.address() << std::endl;
    exit(1);
  }

  // setup DMA region
  host_mem_0_map = NULL;
  if (std::optional<AXI4Config> conf = config.fpga_managed) {
    assert(!config.cpu_managed && "stream should be CPU or FPGA managed");
    assert(conf.has_value());

    auto size_in_bytes = 1ULL << conf->addr_bits;
    printf("Allocating %llu bytes of host memory.\n", size_in_bytes);

    // TODO: check the xclbinutil to see the bank index (put in a MEM_DRAM spot instead of DDR4?)
    host_mem_0 = xrt::bo(device_handle,
                              size_in_bytes,
                              xrt::bo::flags::host_only,
                              0);
    // TODO: unsure about group id... what is this
    uint64_t host_mem_0_paddr = host_mem_0.address();
    printf("DEBUG: bo Flags:%lu PAddr:0x%lu\n", host_mem_0.get_flags(), host_mem_0_paddr);
    host_mem_0_map = host_mem_0.map<char*>();
    printf("DEBUG: bo MapAddr:%p\n", host_mem_0_map);
  }
}

void simif_vitis_t::write(size_t addr, uint32_t data) {
  // addr is really a (32-byte) word address because of zynq implementation
  // addr <<= CTRL_AXI4_SIZE;
  kernel_handle.write_register(addr, data);
}

uint32_t simif_vitis_t::read(size_t addr) {
  // Convert the word address into a byte-address
  // addr <<= CTRL_AXI4_SIZE;
  uint32_t value;
  value = kernel_handle.read_register(addr);
  return value & 0xFFFFFFFF;
}

uint32_t simif_vitis_t::is_write_ready() {
  uint64_t addr = 0x4;
  uint32_t value;
  value = kernel_handle.read_register(addr);
  return value & 0xFFFFFFFF;
}

char *simif_vitis_t::get_memory_base() {
  // add abort if this ptr is null
  assert(host_mem_0_map != NULL);
  printf("get_memory_base: %p\n", host_mem_0_map);
  // TODO: probably need to bo.sync() for caching invalidation
  return host_mem_0_map;
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_vitis_t>(config, args);
}
