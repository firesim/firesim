// See LICENSE for license details.
#include "VMCRReadHarness.h"
#include "verilated.h"
#include <cstdio>
#include <stdexcept>
#include <string>

static void check(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
class ReadTest {
public:
  VMCRReadHarness dut;
  unsigned cycles = 0, consumed = 0, transferred = 0;
  bool ar, r;
  uint32_t data;
  ReadTest() {
    dut.io_nasti_ar_bits_size = 2;
    dut.io_nasti_ar_bits_burst = 1;
    reset();
  }
  void tick() {
    check(++cycles < 20000, "MCRFile cycle budget");
    dut.io_changing = 0x40000000U + cycles;
    dut.io_source_bits = 0xc0010000U + consumed;
    dut.clock = 0;
    dut.eval();
    ar = dut.io_nasti_ar_valid && dut.io_nasti_ar_ready;
    r = dut.io_nasti_r_valid && dut.io_nasti_r_ready;
    data = dut.io_nasti_r_bits_data;
    consumed += dut.io_source_valid && dut.io_source_ready && !dut.reset;
    transferred += r && !dut.reset;
    dut.clock = 1;
    dut.eval();
  }
  void reset() {
    dut.io_nasti_ar_valid = dut.io_nasti_r_ready = dut.io_source_valid = 0;
    dut.reset = 1;
    for (unsigned i = 0; i < 4; ++i)
      tick();
    dut.reset = 0;
    tick();
    check(!dut.io_nasti_r_valid, "reset left a pending response");
  }
  void read(bool source, unsigned identity, bool abort = false) {
    dut.io_source_valid = source;
    dut.io_nasti_ar_bits_addr = source ? 4 : 0;
    dut.io_nasti_ar_bits_id = identity;
    const unsigned before = consumed, responses = transferred;
    bool accepted = false, held = false;
    uint32_t saved = 0;
    for (unsigned i = 0; i < 100; ++i) {
      dut.io_nasti_ar_valid = !accepted;
      dut.io_nasti_r_ready = i >= 12;
      // tick() changes the live register every cycle before sampling the bus.
      tick();
      accepted |= ar;
      // The post-edge response must equal the previously offered response
      // throughout stalls, and at the eventual handshake.
      if (held)
        check(data == saved, "MCRFile response changed under backpressure");
      if (dut.io_nasti_r_valid && !dut.io_nasti_r_ready) {
        check(dut.io_nasti_r_bits_id == identity && !dut.io_nasti_r_bits_resp &&
                  dut.io_nasti_r_bits_last,
              "MCRFile response metadata");
        saved = dut.io_nasti_r_bits_data;
        held = true;
        if (source)
          check(saved == 0xc0010000U + before,
                "source word changed or skipped");
      }
      if (abort && i == 8) {
        check(held && !r, "no pending response before reset");
        reset();
        return;
      }
      if (r) {
        check(accepted && held, "premature read completion");
        check(consumed == before + unsigned(source),
              "source consumed more than once");
        check(transferred == responses + 1, "duplicate response");
        dut.io_nasti_ar_valid = dut.io_nasti_r_ready = dut.io_source_valid = 0;
        for (unsigned j = 0; j < 4; ++j)
          tick();
        check(!dut.io_nasti_r_valid, "response was not retired");
        return;
      }
    }
    throw std::runtime_error("MCRFile read timeout");
  }
};
int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  try {
    check(argc == 2, "one scenario required");
    const std::string name(argv[1]);
    check(name == "changing-register" || name == "decoupled-source" ||
              name == "reset-pending",
          "unknown scenario");
    ReadTest test;
    if (name == "reset-pending")
      test.read(false, 6, true);
    for (unsigned i = 0; i < 32; ++i)
      test.read(name == "decoupled-source", i % 8);
    std::printf("%s: PASS cycles=%u consumed=%u responses=%u\n",
                name.c_str(),
                test.cycles,
                test.consumed,
                test.transferred);
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
