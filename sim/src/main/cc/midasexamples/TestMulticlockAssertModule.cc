// See LICENSE for license details.

#include "TestHarness.h"

#include "bridges/synthesized_assertions.h"

class TestMulticlockAssertModule final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    int assertions_thrown = 0;

    const auto &assert_endpoints = get_bridges<synthesized_assertions_t>();

    for (auto &ep : assert_endpoints)
      ep->init();

    poke("reset", 0);
    poke("fullrate_pulseLength", 2);
    poke("fullrate_cycle", 186);
    poke("halfrate_pulseLength", 2);
    poke("halfrate_cycle", 129);

    step(256, false);
    while (!peek_poke.is_done()) {
      for (auto &ep : assert_endpoints) {
        ep->tick();
        if (ep->terminate()) {
          ep->resume();
          assertions_thrown++;
        }
      }
    }
    expect(assertions_thrown == 3, "EXPECT: Two assertions thrown");
  };
};

TEST_MAIN(TestMulticlockAssertModule)
