// See LICENSE for license details.

#include "VFPGATop.h"
#include "timing_test_addrs.h"
#include "verilated.h"

#include <array>
#include <cstdint>
#include <cstdio>
#include <fstream>
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
      throw std::runtime_error("initialization incomplete");
    std::ofstream curve("target-observations.csv");
    curve << "epoch,iteration,fast,slow,third,sum,mixed,signed,triggerFast,"
             "triggerSlow,triggerThird\n";
    const std::array<unsigned, 9> ports = {ADDR_FAST,
                                           ADDR_SLOW,
                                           ADDR_LAST,
                                           ADDR_SUM,
                                           ADDR_MIXED,
                                           ADDR_SIGNED,
                                           ADDR_TRIGGERFAST,
                                           ADDR_TRIGGERSLOW,
                                           ADDR_TRIGGERTHIRD};
    for (unsigned epoch = 0; epoch < 2; ++epoch) {
      master.write(ADDR_RESET, 1);
      master.write(ADDR_ENABLE, 0);
      master.write(ADDR_SEED, 0);
      master.step(24);
      master.write(ADDR_RESET, 0);
      uint32_t cycles = 0, sum = 0;
      for (unsigned i = 0; i < 96; ++i) {
        const uint32_t seed = (i * 977 + epoch * 3109 + 13) & 65535;
        const bool enabled = i % 5 != 2;
        const unsigned count = 6 * (i % 3 + 1);
        master.write(ADDR_SEED, seed);
        master.write(ADDR_ENABLE, enabled);
        // Reading while stopped introduces different host delays each step.
        for (unsigned delay = 0; delay < i % 7; ++delay)
          (void)master.read(ADDR_FAST);
        master.step(count);
        cycles += count;
        if (enabled)
          sum += seed * count;
        std::array<uint32_t, 9> observed{};
        for (unsigned p = 0; p < ports.size(); ++p)
          observed[p] = master.read(ports[p]);
        if (observed[0] != cycles || observed[1] != cycles / 2 ||
            observed[2] != cycles / 3 || observed[3] != sum ||
            (observed[5] & 65535) != ((-sum) & 65535))
          throw std::runtime_error(
              "closed-form arithmetic/cycle count differs");
        curve << epoch << ',' << i;
        for (auto value : observed)
          curve << ',' << value;
        curve << '\n';
      }
      if (master.read(ADDR_TRIGGERFAST) == 0 ||
          master.read(ADDR_TRIGGERSLOW) == 0 ||
          master.read(ADDR_TRIGGERTHIRD) == 0)
        throw std::runtime_error("trigger path inactive");
    }
    std::puts("PASS: two reset epochs, 192 multirate observations, exact "
              "arithmetic and active trigger sinks");
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
