// See LICENSE for license details.

#include "TestHarness.h"

#include "bridges/synthesized_assertions.h"

class TestMulticlockAssertModule final : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::vector<std::unique_ptr<synthesized_assertions_t>> assert_endpoints;
  void add_bridge_driver(synthesized_assertions_t *bridge) override {
    assert_endpoints.emplace_back(bridge);
  }

  void run_test() override {
    int assertions_thrown = 0;

    for (auto &ep : assert_endpoints)
      ep->init();

    poke("reset", 0);
    poke("fullrate_pulseLength", 2);
    poke("fullrate_cycle", 186);
    poke("halfrate_pulseLength", 2);
    poke("halfrate_cycle", 129);
    step(256, false);
    while (!simif->done()) {
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
