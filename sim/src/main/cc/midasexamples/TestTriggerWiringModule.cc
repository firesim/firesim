// See LICENSE for license details.

#include "TestHarness.h"

class TestTriggerWiringModule final : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::vector<std::unique_ptr<synthesized_assertions_t>> assert_endpoints;
  void add_bridge_driver(synthesized_assertions_t *bridge) override {
    assert_endpoints.emplace_back(bridge);
  }

  bool simulation_complete() {
    bool is_complete = false;
    for (auto &e : assert_endpoints) {
      is_complete |= e->terminate();
    }
    return is_complete;
  }

  int exit_code() {
    for (auto &e : assert_endpoints) {
      if (e->exit_code())
        return e->exit_code();
    }
    return 0;
  }

  void run_test() override {
    int assertions_thrown = 0;

    for (auto &ep : assert_endpoints)
      ep->init();

    poke("reset", 1);
    step(1);
    poke("reset", 0);
    step(10000, false);
    while (!simif->done() && !simulation_complete()) {
      for (auto &ep : assert_endpoints) {
        ep->tick();
      }
    }
    expect(!exit_code(), "No assertions should be thrown");
  }
};

TEST_MAIN(TestTriggerWiringModule)
