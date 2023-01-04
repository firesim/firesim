// See LICENSE for license details.

#include "PrintfTest.h"

class TestPrintfCycleBounds final : public PrintTest {
public:
  using PrintTest::PrintTest;

  void run_test() override {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    poke("reset", 1);
    poke("io_a", 0);
    poke(io_b, 0);
    step(1);
    poke("reset", 0);
    step(1);
    poke("io_a", 1);
    poke(io_b, 1);
    run_and_collect_prints(16000);
  };
};

TEST_MAIN(TestPrintfCycleBounds)
