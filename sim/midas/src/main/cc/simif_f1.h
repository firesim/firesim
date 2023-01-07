#ifndef __SIMIF_F1_H
#define __SIMIF_F1_H

#include "bridges/cpu_managed_stream.h"
#include "simif.h" // from midas

#ifndef SIMULATION_XSIM
#include <fpga_mgmt.h>
#include <fpga_pci.h>
#endif

class simif_f1_t final : public simif_t, public CPUManagedStreamIO {
public:
  simif_f1_t(const TargetConfig &config, const std::vector<std::string> &args);
  ~simif_f1_t();

  int run() { return simulation_run(); }

  void write(size_t addr, uint32_t data) override;
  uint32_t read(size_t addr) override;

  uint32_t is_write_ready();
  void check_rc(int rc, char *infostr);
  void fpga_shutdown();
  void fpga_setup(int slot_id);

private:
  uint32_t mmio_read(size_t addr) override { return read(addr); }

  size_t
  cpu_managed_axi4_write(size_t addr, const char *data, size_t size) override;

  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size) override;

  uint64_t get_beat_bytes() const override {
    return config.cpu_managed->beat_bytes();
  }

#ifdef SIMULATION_XSIM
  char *driver_to_xsim = "/tmp/driver_to_xsim";
  char *xsim_to_driver = "/tmp/xsim_to_driver";
  int driver_to_xsim_fd;
  int xsim_to_driver_fd;
#else
  //    int rc;
  int slot_id;
  int edma_write_fd;
  int edma_read_fd;
  pci_bar_handle_t pci_bar_handle;
#endif
};

#endif // __SIMIF_F1_H
