// See LICENSE for license details.

#include "RiscTest.h"

class TestRiscSRAM final : public RiscTest {
protected:
  using RiscTest::RiscTest;

  void init_app(app_t &app) override {
    expected = 40;
    timeout = 400;
    long_app(app);
  }
};

TEST_MAIN(TestRiscSRAM)
