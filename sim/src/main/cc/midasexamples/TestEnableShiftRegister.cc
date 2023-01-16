// See LICENSE for license details.

#include "TestHarness.h"

class TestEnableShiftRegister final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset();
    std::vector<uint32_t> reg(4, 0);
    for (int i = 0; i < 64; i++) {
      uint32_t in = random() % 16;
      uint32_t shift = random() % 2;
      poke("io_in", in);
      poke("io_shift", shift);
      step(1);
      if (shift) {
        for (int j = 3; j > 0; j--)
          reg[j] = reg[j - 1];
        reg[0] = in;
      }
      expect("io_out", reg[3]);
    }
  }
};

TEST_MAIN(TestEnableShiftRegister)
