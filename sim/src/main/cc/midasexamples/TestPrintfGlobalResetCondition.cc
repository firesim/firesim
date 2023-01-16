// See LICENSE for license details.

#include "PrintfTest.h"

class TestPrintfGlobalResetCondition final : public PrintTest {
public:
  using PrintTest::PrintTest;

  void run_test() override {
    poke("reset", 1);
    // To be safe, must be at least the length of the number of pipeine
    // registers on each reset * maximum clock division.
    step(4);
    poke("reset", 0);
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    run_and_collect_prints(1000);
  };
};

TEST_MAIN(TestPrintfGlobalResetCondition)
