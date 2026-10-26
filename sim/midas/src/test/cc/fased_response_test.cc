// See LICENSE for license details.

#include "VFPGATop.h"
#include "response_test_addrs.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <string>

// An external AXI memory slave and control master for the actual generated
// FPGATop. Respond only after accepting complete single-beat transactions.
class ResponseTest {
  VFPGATop top;
  unsigned cycles = 0;
  bool aw = false, w = false, ar = false;
  unsigned writeId = 0, readId = 0, writeAge = 0, readAge = 0;
  unsigned bCode, rCode, idleR;

public:
  unsigned bResponses = 0, rResponses = 0;

  ResponseTest(unsigned bCode, unsigned rCode, unsigned idleR)
      : bCode(bCode), rCode(rCode), idleR(idleR) {
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

  void tick() {
    if (++cycles > 50000)
      throw std::runtime_error("host-cycle budget exceeded");
    top.mem_0_aw_ready = !aw;
    top.mem_0_w_ready = !w;
    top.mem_0_ar_ready = !ar;
    top.mem_0_b_valid = aw && w && writeAge >= 3;
    top.mem_0_b_bits_id = writeId;
    top.mem_0_b_bits_resp =
        top.mem_0_b_valid ? (bResponses == 0 ? bCode : 0) : 3;
    top.mem_0_r_valid = ar && readAge >= 3;
    top.mem_0_r_bits_id = readId;
    top.mem_0_r_bits_resp =
        top.mem_0_r_valid ? (rResponses == 0 ? rCode : 0) : idleR;
    top.mem_0_r_bits_last = 1;
    top.clock = 0;
    top.eval();
    bool awFire = top.mem_0_aw_valid && top.mem_0_aw_ready;
    bool wFire = top.mem_0_w_valid && top.mem_0_w_ready;
    bool arFire = top.mem_0_ar_valid && top.mem_0_ar_ready;
    bool bFire = top.mem_0_b_valid && top.mem_0_b_ready;
    bool rFire = top.mem_0_r_valid && top.mem_0_r_ready;
    if ((awFire && top.mem_0_aw_bits_len != 0) ||
        (arFire && top.mem_0_ar_bits_len != 0) ||
        (wFire && !top.mem_0_w_bits_last))
      throw std::runtime_error("expected single-beat AXI traffic");
    if (awFire) {
      aw = true;
      writeId = top.mem_0_aw_bits_id;
    }
    if (wFire)
      w = true;
    if (arFire) {
      ar = true;
      readId = top.mem_0_ar_bits_id;
    }
    top.clock = 1;
    top.eval();
    top.clock = 0;
    top.eval();
    if (aw && w)
      ++writeAge;
    if (ar)
      ++readAge;
    if (bFire) {
      aw = w = false;
      writeAge = 0;
      ++bResponses;
    }
    if (rFire) {
      ar = false;
      readAge = 0;
      ++rResponses;
    }
    if (Verilated::gotFinish())
      throw std::runtime_error("RTL assertion terminated simulation");
  }

  void write(uint32_t address, uint32_t data) {
    top.ctrl_aw_bits_addr = address;
    top.ctrl_w_bits_data = data;
    top.ctrl_aw_valid = top.ctrl_w_valid = top.ctrl_b_ready = 1;
    bool awDone = false, wDone = false, done = false;
    while (!done) {
      top.eval();
      bool awFire = top.ctrl_aw_valid && top.ctrl_aw_ready;
      bool wFire = top.ctrl_w_valid && top.ctrl_w_ready;
      done = top.ctrl_b_valid;
      if (done && top.ctrl_b_bits_resp)
        throw std::runtime_error("AXI control write error");
      tick();
      awDone |= awFire;
      wDone |= wFire;
      top.ctrl_aw_valid = !awDone;
      top.ctrl_w_valid = !wDone;
    }
    top.ctrl_b_ready = 0;
  }

  uint32_t read(uint32_t address) {
    top.ctrl_ar_bits_addr = address;
    top.ctrl_ar_valid = top.ctrl_r_ready = 1;
    for (;;) {
      top.eval();
      bool arFire = top.ctrl_ar_valid && top.ctrl_ar_ready;
      bool done = top.ctrl_r_valid;
      uint32_t data = top.ctrl_r_bits_data;
      if (done && top.ctrl_r_bits_resp)
        throw std::runtime_error("AXI control read error");
      tick();
      if (arFire)
        top.ctrl_ar_valid = 0;
      if (done) {
        top.ctrl_r_ready = 0;
        return data;
      }
    }
  }

  void step(unsigned count) {
    write(ADDR_STEP, count);
    while (!read(ADDR_DONE)) {
    }
    while (!read(ADDR_PRECISE_PEEKABLE)) {
    }
  }
};

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    if (argc != 2)
      throw std::runtime_error("expected scenario");
    std::string name = argv[1];
    bool writes = name != "read-control";
    bool reads = name == "read-control" || name == "mixed-errors";
    unsigned bCode = name == "write-slverr"                             ? 2
                     : name == "write-decerr" || name == "mixed-errors" ? 3
                                                                        : 0;
    unsigned rCode = reads ? 2 : 0;
    unsigned idleR = name == "okay-control"   ? 3
                     : name == "write-slverr" ? 0
                                              : 1;
    ResponseTest test(bCode, rCode, idleR);
    if (!test.read(ADDR_INIT_DONE))
      throw std::runtime_error("initialization incomplete");
    if (test.read(ADDR_brespError) || test.read(ADDR_rrespError))
      throw std::runtime_error(
          "invalid response payload latched before any transfer");
    test.write(ADDR_writeLatency, 7);
    test.write(ADDR_readLatency, 7);
    test.write(ADDR_writeMaxReqs, 2);
    test.write(ADDR_readMaxReqs, 2);
    test.write(ADDR_relaxFunctionalModel, 0);
    test.write(ADDR_WRITES, writes);
    test.write(ADDR_READS, reads);
    test.write(ADDR_RESET, 1);
    test.step(8);
    test.write(ADDR_RESET, 0);
    test.step(512);
    unsigned observedB = test.read(ADDR_brespError);
    unsigned observedR = test.read(ADDR_rrespError);
    unsigned targetB = test.read(ADDR_WRITE_COUNT);
    unsigned targetR = test.read(ADDR_READ_COUNT);
    std::printf("%s: host B/R=%u/%u target B/R=%u/%u brespError=%u expected=%u "
                "rrespError=%u expected=%u\n",
                name.c_str(),
                test.bResponses,
                test.rResponses,
                targetB,
                targetR,
                observedB,
                bCode,
                observedR,
                rCode);
    return test.bResponses == (writes ? 4u : 0u) &&
                   test.rResponses == (reads ? 4u : 0u) &&
                   targetB == test.bResponses && targetR == test.rResponses &&
                   observedB == bCode && observedR == rCode
               ? 0
               : 1;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
