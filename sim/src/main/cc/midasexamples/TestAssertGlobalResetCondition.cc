// See LICENSE for license details.

#include "TestHarness.h"
#include "bridges/synthesized_assertions.h"

class TestAssertGlobalResetCondition final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset(2);
    step(40000, false);
    while (!peek_poke.is_done()) {
      for (auto &ep : get_bridges<synthesized_assertions_t>()) {
        ep->tick();
        if (ep->terminate()) {
          abort();
        }
      }
    }
  };
};

TEST_MAIN(TestAssertGlobalResetCondition)
