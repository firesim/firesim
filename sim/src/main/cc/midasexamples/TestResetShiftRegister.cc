// See LICENSE for license details.

#include "TestHarness.h"

class TestResetShiftRegister final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    std::vector<uint32_t> ins(5, 0);
    int k = 0;
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t in = random() % 16;
      uint32_t shift = random() % 2;
      if (shift == 1)
        ins[k % 5] = in;
      poke("io_in", in);
      poke("io_shift", shift);
      step(1);
      if (shift == 1)
        k++;
      expect("io_out", cycles() < 3 ? 0 : ins[(k + 1) % 5]);
    }
  }
};

TEST_MAIN(TestResetShiftRegister)
