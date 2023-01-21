// See LICENSE for license details.

#ifndef MIDASEXAMPLES_AUTOCOUNTERMODULE_H
#define MIDASEXAMPLES_AUTOCOUNTERMODULE_H

#include "TestHarness.h"

class AutoCounterTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  ~AutoCounterTest() override = default;

  std::vector<std::unique_ptr<autocounter_t>> autocounter_endpoints;
  void add_bridge_driver(autocounter_t *bridge) override {
    autocounter_endpoints.emplace_back(bridge);
  }

  void run_and_collect(int cycles) {
    step(cycles, false);
    while (!simif->done()) {
      for (auto &autocounter_endpoint : autocounter_endpoints) {
        autocounter_endpoint->tick();
      }
    }
    for (auto &autocounter_endpoint : autocounter_endpoints) {
      autocounter_endpoint->finish();
    }
  };
};

#endif // MIDASEXAMPLES_AUTOCOUNTERMODULE_H
