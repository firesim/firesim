#ifndef __SIMIF_VITIS_H
#define __SIMIF_VITIS_H

#include "simif.h"

#include "experimental/xrt_device.h"
#include "experimental/xrt_ip.h"
#include "experimental/xrt_kernel.h"

class simif_vitis_t final : public simif_t {
public:
  simif_vitis_t(const std::vector<std::string> &args);
  ~simif_vitis_t() {}

  // Will be used once FPGA-managed AXI4 is fully plumbed through the shim
  // to setup the FPGAManagedStream engine.
  void host_mmio_init() override{};

  int run() override { return simulation_run(); }

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;
  size_t pull(unsigned stream_idx,
              void *dest,
              size_t num_bytes,
              size_t threshold_bytes) override;
  size_t push(unsigned stream_idx,
              void *src,
              size_t num_bytes,
              size_t threshold_bytes) override;
  uint32_t is_write_ready();

  void pull_flush(unsigned int stream_no) override {}
  void push_flush(unsigned int stream_no) override {}

private:
  int slotid;
  std::string binary_file;
  xrt::device device_handle;
  xrt::uuid uuid;
  xrt::ip kernel_handle;
  xrt::run run_handle;
};

#endif // __SIMIF_VITIS_H
