// See LICENSE for license details.

#include "PrintfTest.h"

class TestMulticlockPrintfModule : public PrintTest {
public:
  using PrintTest::PrintTest;

  void run_test() override {
    step(1);
    poke("reset", 0);
    run_and_collect_prints(256);
  };
};

TEST_MAIN(TestMulticlockPrintfModule)
