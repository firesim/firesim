// See LICENSE for license details.

#include "TestHarness.h"

class TestMultiSRAMFMR final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset();
    step(10000);
  }
};

TEST_MAIN(TestMultiSRAMFMR)
