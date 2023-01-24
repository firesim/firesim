// See LICENSE for license details.

#include "PrintfTest.h"

class TestNarrowPrintfModule : public PrintTest {
public:
  using PrintTest::PrintTest;

  void run_test() override {
    poke("reset", 1);
    poke("io_enable", 0);
    step(1);
    poke("io_enable", 1);
    poke("reset", 0);
    step(4);
    // Test idle-cycle rollover
    poke("io_enable", 0);
    step(256);
    poke("io_enable", 1);
    run_and_collect_prints(256);
  }
};

TEST_MAIN(TestNarrowPrintfModule)
