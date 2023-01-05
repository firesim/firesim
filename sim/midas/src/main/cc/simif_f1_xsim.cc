#include <cassert>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "bridges/cpu_managed_stream.h"
#include "core/simif.h"

class simif_f1_xsim_t final : public simif_t, public CPUManagedStreamIO {
public:
  simif_f1_xsim_t(const TargetConfig &config,
                  const std::vector<std::string> &args);
  ~simif_f1_xsim_t();

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

  const char *driver_to_xsim = "/tmp/driver_to_xsim";
  const char *xsim_to_driver = "/tmp/xsim_to_driver";
  int driver_to_xsim_fd;
  int xsim_to_driver_fd;
};

simif_f1_xsim_t::simif_f1_xsim_t(const TargetConfig &config,
                                 const std::vector<std::string> &args)
    : simif_t(config, args) {
  mkfifo(driver_to_xsim, 0666);
  fprintf(stderr, "opening driver to xsim\n");
  driver_to_xsim_fd = open(driver_to_xsim, O_WRONLY);
  fprintf(stderr, "opening xsim to driver\n");
  xsim_to_driver_fd = open(xsim_to_driver, O_RDONLY);
}

void simif_f1_xsim_t::check_rc(int rc, char *infostr) {}

void simif_f1_xsim_t::fpga_shutdown() {}

void simif_f1_xsim_t::fpga_setup(int slot_id) {}

simif_f1_xsim_t::~simif_f1_xsim_t() { fpga_shutdown(); }

void simif_f1_xsim_t::write(size_t addr, uint32_t data) {
  uint64_t cmd = (((uint64_t)(0x80000000 | addr)) << 32) | (uint64_t)data;
  char *buf = (char *)&cmd;
  ::write(driver_to_xsim_fd, buf, 8);
}

uint32_t simif_f1_xsim_t::read(size_t addr) {
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
}

size_t
simif_f1_xsim_t::cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
  assert(false); // PCIS is unsupported in FPGA-level metasimulation
}

size_t simif_f1_xsim_t::cpu_managed_axi4_write(size_t addr,
                                               const char *data,
                                               size_t size) {
  assert(false); // PCIS is unsupported in FPGA-level metasimulation
}

uint32_t simif_f1_xsim_t::is_write_ready() {
  uint64_t addr = 0x4;
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
}

std::unique_ptr<simif_t>
create_simif(const TargetConfig &config, int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
  return std::make_unique<simif_f1_xsim_t>(config, args);
}
