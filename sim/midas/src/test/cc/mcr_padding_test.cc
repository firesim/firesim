// See LICENSE for license details.
#include "VMCRPaddingHarness.h"
#include "verilated.h"
#include <cstdio>
#include <stdexcept>
#include <string>

static void check(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}

class PaddingTest {
public:
  VMCRPaddingHarness dut;
  bool ar = false, aw = false, w = false, r = false, b = false;
  uint32_t data = 0, rid = 0, bid = 0;
  unsigned cycles = 0;
  void tick() {
    check(++cycles < 20000, "total cycle budget");
    dut.clock = 0;
    dut.eval();
    ar = dut.io_nasti_ar_valid && dut.io_nasti_ar_ready;
    aw = dut.io_nasti_aw_valid && dut.io_nasti_aw_ready;
    w = dut.io_nasti_w_valid && dut.io_nasti_w_ready;
    r = dut.io_nasti_r_valid && dut.io_nasti_r_ready;
    b = dut.io_nasti_b_valid && dut.io_nasti_b_ready;
    data = dut.io_nasti_r_bits_data;
    rid = dut.io_nasti_r_bits_id;
    bid = dut.io_nasti_b_bits_id;
    if (r)
      check(!dut.io_nasti_r_bits_resp && dut.io_nasti_r_bits_last,
            "read response metadata");
    if (b)
      check(!dut.io_nasti_b_bits_resp, "write response metadata");
    dut.clock = 1;
    dut.eval();
  }
  PaddingTest() {
    dut.io_nasti_ar_valid = dut.io_nasti_aw_valid = dut.io_nasti_w_valid = 0;
    dut.io_nasti_r_ready = dut.io_nasti_b_ready = 0;
    dut.io_nasti_ar_bits_size = dut.io_nasti_aw_bits_size = 2;
    dut.io_nasti_ar_bits_burst = dut.io_nasti_aw_bits_burst = 1;
    dut.io_nasti_ar_bits_len = dut.io_nasti_aw_bits_len = 0;
    dut.io_nasti_w_bits_strb = 15;
    dut.io_nasti_w_bits_last = 1;
    dut.reset = 1;
    for (unsigned i = 0; i < 4; ++i)
      tick();
    dut.reset = 0;
    tick();
  }
  void read(unsigned slot, unsigned identity) {
    const auto before = dut.io_reads;
    const bool padding = slot == 3;
    dut.io_nasti_ar_bits_addr = slot * 4;
    dut.io_nasti_ar_bits_id = identity;
    bool accepted = false;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_nasti_ar_valid = !accepted;
      dut.io_nasti_r_ready = i >= 12;
      tick();
      accepted |= ar;
      if (r) {
        check(accepted && rid == identity, "read identity");
        check(data == (padding ? 0 : 0x12340000U + slot), "read value");
        check(dut.io_reads == before + unsigned(!padding), "read side effects");
        dut.io_nasti_ar_valid = dut.io_nasti_r_ready = 0;
        for (unsigned j = 0; j < 4; ++j)
          tick();
        check(!dut.io_nasti_r_valid, "duplicate read response");
        return;
      }
    }
    throw std::runtime_error("read response deadline");
  }
  void write(unsigned slot, unsigned identity) {
    const auto before = dut.io_writes;
    dut.io_nasti_aw_bits_addr = slot * 4;
    dut.io_nasti_aw_bits_id = identity;
    dut.io_nasti_w_bits_data = 0xabcdef01;
    bool addressed = false, written = false;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_nasti_aw_valid = !addressed;
      dut.io_nasti_w_valid = !written && i >= 4;
      dut.io_nasti_b_ready = i >= 12;
      tick();
      addressed |= aw;
      written |= w;
      if (b) {
        check(addressed && written && bid == identity, "write identity");
        check(dut.io_writes == before + unsigned(slot != 3),
              "write side effects");
        dut.io_nasti_aw_valid = dut.io_nasti_w_valid = dut.io_nasti_b_ready = 0;
        for (unsigned j = 0; j < 4; ++j)
          tick();
        check(!dut.io_nasti_b_valid, "duplicate write response");
        return;
      }
    }
    throw std::runtime_error("write response deadline");
  }
};

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    check(argc == 2, "one scenario required");
    const std::string name(argv[1]);
    check(name == "valid-read" || name == "valid-write" ||
              name == "padding-read" || name == "padding-write",
          "unknown scenario");
    PaddingTest test;
    for (unsigned i = 0; i < 16; ++i) {
      const auto slot = name.find("padding") == 0 ? 3U : i % 3;
      if (name.find("write") != std::string::npos)
        test.write(slot, i % 8);
      else
        test.read(slot, i % 8);
      // A padded slot must not poison a later mapped access.
      test.read(i % 3, (i + 1) % 8);
      test.write(i % 3, (i + 2) % 8);
    }
    std::printf("%s: PASS cycles=%u reads=%u writes=%u\n",
                name.c_str(),
                test.cycles,
                test.dut.io_reads,
                test.dut.io_writes);
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
