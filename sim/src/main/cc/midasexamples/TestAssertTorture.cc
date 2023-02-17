// See LICENSE for license details.

#include "AssertTest.h"

class TestAssertTorture final : public AssertTest {
public:
  using AssertTest::AssertTest;

  void run_test() override {
    target_reset(2);
    peek_poke.step(40000, false);
    while (!peek_poke.is_done()) {
      for (auto &ep : assert_endpoints) {
        ep->tick();
        if (ep->terminate()) {
          ep->resume();
        }
      }
    }
  };
};

TEST_MAIN(TestAssertTorture)
