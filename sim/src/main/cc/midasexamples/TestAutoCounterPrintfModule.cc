// See LICENSE for license details.

#include "PrintfTest.h"

class TestAutoCounterModule : public PrintTest {
public:
  using PrintTest::PrintTest;

  void run_test() override {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    poke("reset", 1);
    poke("io_a", 0);
    step(1);
    poke("reset", 0);
    step(1);
    poke("io_a", 1);
    run_and_collect_prints(3000);
  };
};

TEST_MAIN(TestAutoCounterModule)
