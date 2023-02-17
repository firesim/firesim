// See LICENSE for license details.

#include "AssertTest.h"

class TestAssertGlobalResetCondition final : public AssertTest {
public:
  using AssertTest::AssertTest;

  void run_test() override {
    target_reset(2);
    step(40000, false);
    while (!peek_poke.is_done()) {
      for (auto &ep : assert_endpoints) {
        ep->tick();
        if (ep->terminate()) {
          abort();
        }
      }
    }
  };
};

TEST_MAIN(TestAssertGlobalResetCondition)
