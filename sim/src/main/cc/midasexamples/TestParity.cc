// See LICENSE for license details.

#include "TestHarness.h"

class TestParity final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    uint32_t is_odd = 0;
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t bit = random() % 2;
      poke("io_in", bit);
      step(1);
      is_odd = (is_odd + bit) % 2;
      expect("io_out", is_odd);
    }
  }
};

TEST_MAIN(TestParity)
