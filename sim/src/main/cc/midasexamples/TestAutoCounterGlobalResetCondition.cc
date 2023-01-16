// See LICENSE for license details.

#include "AutoCounterTest.h"

class TestAutoCounterGlobalResetCondition : public AutoCounterTest {
public:
  using AutoCounterTest::AutoCounterTest;

  void run_test() override {
    for (auto &autocounter_endpoint : autocounter_endpoints) {
      autocounter_endpoint->init();
    }
    poke("reset", 1);
    step(4);
    poke("reset", 0);
    run_and_collect(3000);
  };
};

TEST_MAIN(TestAutoCounterGlobalResetCondition)
