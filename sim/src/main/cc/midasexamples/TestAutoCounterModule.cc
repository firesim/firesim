// See LICENSE for license details.

#include "AutoCounterTest.h"

class TestAutoCounterModule : public AutoCounterTest {
public:
  using AutoCounterTest::AutoCounterTest;

  void run_test() override {
    for (auto &autocounter_endpoint : autocounter_endpoints) {
      autocounter_endpoint->init();
    }
    poke("reset", 1);
    poke("io_a", 0);
    step(1);
    poke("reset", 0);
    step(1);
    poke("io_a", 1);
    run_and_collect(3000);
  };
};

TEST_MAIN(TestAutoCounterModule)
