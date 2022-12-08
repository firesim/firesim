// See LICENSE for license details.

#include "TestHarness.h"

class TestAssertTorture final : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::vector<std::unique_ptr<synthesized_assertions_t>> assert_endpoints;
  void add_bridge_driver(synthesized_assertions_t *bridge) override {
    assert_endpoints.emplace_back(bridge);
  }

  void run_test() override {
    for (auto &ep : assert_endpoints)
      ep->init();

    target_reset(2);
    step(40000, false);
    while (!simif->done()) {
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
