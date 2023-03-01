// See LICENSE for license details.

#include "AutoCounterTest.h"

class TestMulticlockAutoCounterModule final : public AutoCounterTest {
public:
  using AutoCounterTest::AutoCounterTest;

  void run_test() override {
    poke("reset", 1);
    step(1);
    poke("reset", 0);
    run_and_collect(3000);
  };
};

TEST_MAIN(TestMulticlockAutoCounterModule)
