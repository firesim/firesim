// See LICENSE for license details.
#include "VSlashDebugAXIAudit.h"
#include "verilated.h"
#include <cstdio>
#include <stdexcept>
#include <string>

void check(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
class AuditTest {
public:
  VSlashDebugAXIAudit dut;
  unsigned cycles = 0;
  bool ar, r, aw, w, b;
  uint32_t data, id;
  AuditTest() {
    dut.io_axi_ar_bits_size = dut.io_axi_aw_bits_size = 2;
    dut.io_axi_ar_bits_burst = dut.io_axi_aw_bits_burst = 1;
    dut.io_axi_w_bits_last = 1;
    dut.io_axi_w_bits_strb = 15;
    dut.reset = 1;
    for (unsigned i = 0; i < 4; ++i)
      tick();
    dut.reset = 0;
    tick();
  }
  void tick() {
    check(++cycles < 20000, "audit cycle budget");
    dut.clock = 0;
    dut.eval();
    ar = dut.io_axi_ar_valid && dut.io_axi_ar_ready;
    r = dut.io_axi_r_valid && dut.io_axi_r_ready;
    aw = dut.io_axi_aw_valid && dut.io_axi_aw_ready;
    w = dut.io_axi_w_valid && dut.io_axi_w_ready;
    b = dut.io_axi_b_valid && dut.io_axi_b_ready;
    data = dut.io_axi_r_bits_data;
    id = dut.io_axi_r_bits_id;
    if (r)
      check(!dut.io_axi_r_bits_resp && dut.io_axi_r_bits_last,
            "audit read response");
    if (b)
      check(!dut.io_axi_b_bits_resp, "audit write response");
    dut.clock = 1;
    dut.eval();
  }
  uint32_t read(unsigned address, bool stall = false) {
    dut.io_axi_ar_bits_addr = address;
    dut.io_axi_ar_bits_id = 6;
    bool accepted = false, held = false;
    uint32_t heldData = 0;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_axi_ar_valid = !accepted;
      dut.io_axi_r_ready = !stall || i >= 8;
      dut.clock = 0;
      dut.eval();
      if (held)
        check(dut.io_axi_r_valid && dut.io_axi_r_bits_data == heldData &&
                  dut.io_axi_r_bits_id == 6,
              "audit response changed while stalled");
      held = dut.io_axi_r_valid && !dut.io_axi_r_ready;
      heldData = dut.io_axi_r_bits_data;
      tick();
      accepted |= ar;
      if (r) {
        check(accepted && id == 6, "audit read ID or premature response");
        dut.io_axi_ar_valid = 0;
        dut.io_axi_r_ready = 0;
        return data;
      }
    }
    throw std::runtime_error("audit read timeout");
  }
  void write(unsigned order, unsigned identity) {
    dut.io_axi_aw_bits_addr = 0;
    dut.io_axi_aw_bits_id = identity;
    dut.io_axi_w_bits_data = 0xdeadbeef;
    bool gotAW = false, gotW = false;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_axi_aw_valid = !gotAW && (order != 1 || i >= 4);
      dut.io_axi_w_valid = !gotW && (order != 0 || i >= 4);
      dut.io_axi_b_ready = i >= 12;
      dut.clock = 0;
      dut.eval();
      if (dut.io_axi_b_valid)
        check(dut.io_axi_b_bits_id == identity, "audit write ID");
      tick();
      gotAW |= aw;
      gotW |= w;
      if (b) {
        check(gotAW && gotW, "audit write response before AW/W");
        dut.io_axi_aw_valid = dut.io_axi_w_valid = dut.io_axi_b_ready = 0;
        return;
      }
    }
    throw std::runtime_error("audit write timeout");
  }
  void inject() {
    dut.io_hubAR = 1;
    dut.io_hubAddress = 0;
    tick();
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_hostAR = i % 2 == 0;
      dut.io_hubAR = i < 99;
      dut.io_hubAddress = (i + 1) * 4;
      dut.io_hubR = 1;
      dut.io_hubData = 0xc0010000U ^ i;
      dut.io_hubResp = i % 3;
      dut.io_hubLast = i % 2;
      dut.io_axisRX = i < 80;
      dut.io_axisData = 0xbeef0000U ^ i;
      dut.io_axisLast = i % 7 == 0;
      dut.io_axisTX = i % 4 == 0;
      tick();
    }
    dut.io_hostAR = dut.io_hubAR = dut.io_hubR = dut.io_axisRX = dut.io_axisTX =
        0;
    tick();
  }
  void verify(bool stalls) {
    auto reg = [&](unsigned address) { return read(address, stalls); };
    check(reg(8) == 50 && reg(12) == 100 && reg(16) == 100 && reg(20) == 66,
          "audit counters");
    check(reg(24) == 396 && reg(28) == (0xc0010000U ^ 99) && reg(32) == 4,
          "audit last response");
    check(reg(36) == 64 && reg(40) == 80 && reg(44) == 64 && reg(48) == 25,
          "audit trace saturation/AXIS counts");
    for (unsigned repeat = 0; repeat < 2; ++repeat)
      for (unsigned i = 0; i < 64; ++i) {
        check(reg(0x100 + i * 8) == (0xc0010000U ^ i),
              "audit response data trace");
        check(reg(0x104 + i * 8) == ((i % 2) << 31 | (i % 3) << 29 | i * 4),
              "audit response metadata trace");
        check(reg(0x400 + i * 8) == (0xbeef0000U ^ i) &&
                  reg(0x404 + i * 8) == (i % 7 == 0),
              "audit AXIS trace");
      }
    check(reg(36) == 64 && reg(44) == 64 && reg(12) == 100,
          "audit reads modified passive state");
  }
};
int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    check(argc == 2, "one audit scenario required");
    std::string name(argv[1]);
    check(name == "trace" || name == "read-stalls" || name == "write-order",
          "unknown audit scenario");
    AuditTest test;
    test.inject();
    if (name == "write-order")
      for (unsigned i = 0; i < 24; ++i)
        test.write(i % 3, i % 8);
    test.verify(name == "read-stalls");
    std::printf("%s: PASS cycles=%u\n", name.c_str(), test.cycles);
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
