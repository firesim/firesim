#ifndef __SIMIF_VITIS_H
#define __SIMIF_VITIS_H

#include <experimental/xrt_device.h>
#include <experimental/xrt_ip.h>
#include <experimental/xrt_kernel.h>

#include "bridges/fpga_managed_stream.h"
#include "simif.h"

class simif_vitis_t final : public simif_t, public FPGAManagedStreamIO {
public:
  simif_vitis_t(const TargetConfig &config,
                const std::vector<std::string> &args);
  ~simif_vitis_t() {}

  int run() { return simulation_run(); }

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  uint32_t is_write_ready();

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

#endif // __SIMIF_VITIS_H
