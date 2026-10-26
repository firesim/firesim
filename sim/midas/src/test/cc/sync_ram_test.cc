// See LICENSE for license details.

#include "VFPGATop.h"
#include "ram_test_addrs.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>
#include <stdexcept>

// External AXI control master. All access is to the simulated FPGATop.
class ControlMaster {
  VFPGATop top;
  unsigned cycles = 0;

public:
  void tick() {
    if (++cycles > 1000000)
      throw std::runtime_error("host-cycle budget exceeded");
    top.clock = 0;
    top.eval();
    top.clock = 1;
    top.eval();
    top.clock = 0;
    top.eval();
  }

  ControlMaster() {
    top.ctrl_aw_bits_size = top.ctrl_ar_bits_size = 2;
    top.ctrl_aw_bits_burst = top.ctrl_ar_bits_burst = 1;
    top.ctrl_w_bits_strb = 15;
    top.ctrl_w_bits_last = 1;
    top.reset = 1;
    for (int i = 0; i < 10; ++i)
      tick();
    top.reset = 0;
    for (int i = 0; i < 100; ++i)
      tick();
  }

  void write(uint32_t address, uint32_t data) {
    top.ctrl_aw_bits_addr = address;
    top.ctrl_w_bits_data = data;
    top.ctrl_aw_valid = top.ctrl_w_valid = 1;
    top.ctrl_b_ready = 1;
    bool aw = false, w = false, b = false;
    while (!b) {
      top.eval();
      bool awFire = top.ctrl_aw_valid && top.ctrl_aw_ready;
      bool wFire = top.ctrl_w_valid && top.ctrl_w_ready;
      b = top.ctrl_b_valid;
      if (b && top.ctrl_b_bits_resp)
        throw std::runtime_error("AXI control write error");
      tick();
      aw |= awFire;
      w |= wFire;
      top.ctrl_aw_valid = !aw;
      top.ctrl_w_valid = !w;
    }
    top.ctrl_b_ready = 0;
  }

  uint32_t read(uint32_t address) {
    top.ctrl_ar_bits_addr = address;
    top.ctrl_ar_valid = top.ctrl_r_ready = 1;
    for (;;) {
      top.eval();
      bool arFire = top.ctrl_ar_valid && top.ctrl_ar_ready;
      bool rFire = top.ctrl_r_valid;
      uint32_t data = top.ctrl_r_bits_data;
      if (rFire && top.ctrl_r_bits_resp)
        throw std::runtime_error("AXI control read error");
      tick();
      if (arFire)
        top.ctrl_ar_valid = 0;
      if (rFire) {
        top.ctrl_r_ready = 0;
        return data;
      }
    }
  }

  void step(uint32_t cycles) {
    write(ADDR_STEP, cycles);
    while (!read(ADDR_DONE)) {
    }
    while (!read(ADDR_PRECISE_PEEKABLE)) {
    }
  }
};

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    ControlMaster master;
    if (!master.read(ADDR_INIT_DONE))
      throw std::runtime_error("simulator initialization incomplete");
    master.write(ADDR_RESET, 1);
    master.step(8);
    master.write(ADDR_RESET, 0);
    master.step(512);
    auto cycles = master.read(ADDR_CYCLES);
    auto checks = master.read(ADDR_CHECKS);
    auto errors = master.read(ADDR_ERRORS);
    auto signature = master.read(ADDR_SIGNATURE);
    std::printf("RAM regression: cycles=%u checks=%u errors=%u signature=%u\n",
                cycles,
                checks,
                errors,
                signature);
    return cycles == 512 && checks > 500 && errors == 0 ? 0 : 1;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
