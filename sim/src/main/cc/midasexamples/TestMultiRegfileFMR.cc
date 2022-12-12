// See LICENSE for license details.

#include <cstdio>
#include <cstdlib>
#include <utility>

#include "TestHarness.h"

class TestMultiRegfileFMR : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset();
    step(10000);
  }
};

TEST_MAIN(TestMultiRegfileFMR)
