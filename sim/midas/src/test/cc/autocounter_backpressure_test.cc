// See LICENSE for license details.
#include "VAutoCounterBackpressureHarness.h"
#include "autocounter_regs.h"
#include "verilated.h"
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>

static void check(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}

class CounterTest {
public:
  VAutoCounterBackpressureHarness dut;
  unsigned ticks = 0, accepted = 0, limit = 65;
  bool paused = true, gap = false;
  bool aw = false, w = false, b = false, ar = false, r = false;
  uint32_t data = 0;
  FILE *trace;
  CounterTest(const std::string &scenario)
      : trace(std::fopen((scenario + "-samples.csv").c_str(), "w")) {
    check(trace, "cannot open sample trace");
    std::fprintf(trace,
                 "host_cycle,accepted_tokens,local_cycle,accum,identity\n");
    dut.io_ctrl_aw_bits_size = dut.io_ctrl_ar_bits_size = 2;
    dut.io_ctrl_aw_bits_burst = dut.io_ctrl_ar_bits_burst = 1;
    dut.io_ctrl_w_bits_strb = 15;
    reset();
  }
  ~CounterTest() { std::fclose(trace); }
  void tick() {
    check(++ticks < 100000, "AutoCounter host cycle budget exceeded");
    dut.io_valid = !paused && accepted < limit;
    dut.io_returnReady = ticks % 17 >= 4;
    dut.io_trigger = !gap || accepted < 16 || accepted >= 32;
    dut.io_underReset = accepted < 3;
    dut.io_accum = accepted % 7 + 1;
    dut.io_identity = accepted;
    dut.clock = 0;
    dut.eval();
    const bool fire = dut.io_valid && dut.io_ready && !dut.reset;
    aw = dut.io_ctrl_aw_valid && dut.io_ctrl_aw_ready;
    w = dut.io_ctrl_w_valid && dut.io_ctrl_w_ready;
    b = dut.io_ctrl_b_valid && dut.io_ctrl_b_ready;
    ar = dut.io_ctrl_ar_valid && dut.io_ctrl_ar_ready;
    r = dut.io_ctrl_r_valid && dut.io_ctrl_r_ready;
    data = dut.io_ctrl_r_bits_data;
    if (r)
      check(!dut.io_ctrl_r_bits_resp && dut.io_ctrl_r_bits_last,
            "AutoCounter MMIO read error");
    if (b)
      check(!dut.io_ctrl_b_bits_resp, "AutoCounter MMIO write error");
    dut.clock = 1;
    dut.eval();
    accepted += fire;
  }
  void reset() {
    paused = true;
    dut.io_ctrl_aw_valid = dut.io_ctrl_w_valid = dut.io_ctrl_ar_valid = 0;
    dut.io_ctrl_b_ready = dut.io_ctrl_r_ready = 0;
    dut.reset = 1;
    for (unsigned i = 0; i < 5; ++i)
      tick();
    dut.reset = 0;
    accepted = 0;
    tick();
  }
  void write(unsigned address, uint32_t value) {
    dut.io_ctrl_aw_bits_addr = address;
    dut.io_ctrl_w_bits_data = value;
    dut.io_ctrl_b_ready = 1;
    bool gotAW = false, gotW = false;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_ctrl_aw_valid = !gotAW;
      dut.io_ctrl_w_valid = !gotW;
      tick();
      gotAW |= aw;
      gotW |= w;
      if (b) {
        check(gotAW && gotW, "write response before request");
        dut.io_ctrl_aw_valid = dut.io_ctrl_w_valid = dut.io_ctrl_b_ready = 0;
        return;
      }
    }
    throw std::runtime_error("AutoCounter MMIO write timeout");
  }
  uint32_t read(unsigned address) {
    dut.io_ctrl_ar_bits_addr = address;
    bool gotAR = false;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_ctrl_ar_valid = !gotAR;
      dut.io_ctrl_r_ready = i >= 7;
      tick();
      gotAR |= ar;
      if (r) {
        check(gotAR, "read response before request");
        dut.io_ctrl_ar_valid = dut.io_ctrl_r_ready = 0;
        return data;
      }
    }
    throw std::runtime_error("AutoCounter MMIO read timeout");
  }
  void init() {
    write(REG_readrate_low, 3);
    write(REG_readrate_high, 0);
    write(REG_init_done, 1);
  }
  uint64_t read64(unsigned lo, unsigned hi) {
    const uint64_t low = read(lo);
    return low | (uint64_t(read(hi)) << 32);
  }
  void until(unsigned tokens) {
    paused = false;
    while (accepted < tokens)
      tick();
    paused = true;
  }
  void sample(unsigned cycle) {
    check(read(REG_countersready), "expected sample missing");
    const auto local = read64(REG_autocounter_low_local_cycle,
                              REG_autocounter_high_local_cycle);
    const auto accum =
        read64(REG_autocounter_low_accum, REG_autocounter_high_accum);
    const auto identity =
        read64(REG_autocounter_low_identity, REG_autocounter_high_identity);
    std::fprintf(trace,
                 "%u,%u,%llu,%llu,%llu\n",
                 ticks,
                 accepted,
                 (unsigned long long)local,
                 (unsigned long long)accum,
                 (unsigned long long)identity);
    unsigned expected = 0;
    for (unsigned i = 3; i < cycle; ++i)
      expected += i % 7 + 1;
    check(local == cycle,
          "AutoCounter sample was lost, repeated, or reordered");
    check(accum == expected, "AutoCounter accumulation changed under stalls");
    check(identity == cycle, "AutoCounter identity changed under stalls");
    write(REG_readdone, 1);
    for (unsigned i = 0; i < 4; ++i)
      tick();
  }
  void run(const std::string &scenario) {
    gap = scenario == "trigger-gap";
    init();
    if (scenario == "reset-pending") {
      until(5);
      check(read(REG_countersready), "no queued sample before reset");
      reset();
      check(!read(REG_countersready), "reset left a stale sample");
      init();
    }
    const bool slow = scenario == "slow-drain" || gap;
    if (slow) {
      paused = false;
      for (unsigned i = 0; i < 500; ++i)
        tick();
    }
    unsigned count = 0;
    for (unsigned cycle = 4; cycle <= 64; cycle += 4) {
      if (gap && cycle >= 16 && cycle < 32)
        continue;
      if (!slow)
        until(cycle + 1);
      sample(cycle);
      ++count;
    }
    paused = false;
    while (accepted < limit)
      tick();
    for (unsigned i = 0; i < 100; ++i)
      tick();
    check(!read(REG_countersready), "duplicate sample after final token");
    std::printf("%s: PASS target_tokens=%u samples=%u host_cycles=%u\n",
                scenario.c_str(),
                accepted,
                count,
                ticks);
  }
};

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    check(argc == 2, "one scenario required");
    const std::string scenario(argv[1]);
    check(scenario == "fast-drain" || scenario == "slow-drain" ||
              scenario == "trigger-gap" || scenario == "reset-pending",
          "unknown scenario");
    CounterTest(scenario).run(scenario);
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
