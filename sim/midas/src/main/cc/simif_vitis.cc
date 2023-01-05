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
    : simif_t(config, args) {
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

  // Intialize FPGA-DRAM regions.
  // The final argument here is the bank index for the dram channel.
  // I used xclbinutil to find this
  // https://xilinx.github.io/XRT/master/html/xclbintools.html
  auto fpga_mem_0 = xrt::bo(device_handle,
                            u250_dram_channel_size_bytes,
                            xrt::bo::flags::device_only,
                            0);

  if (fpga_mem_0.address() != u250_dram_expected_offset) {
    std::cerr
        << "Allocated device_only buffer address not match the expected offset."
        << std::endl;
    std::cerr << "Expected: " << u250_dram_expected_offset
              << "Received: " << fpga_mem_0.address() << std::endl;
    exit(1);
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
  std::cerr << "FPGA-managed streams are not yet supported";
  abort();
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_vitis_t>(config, args);
}
