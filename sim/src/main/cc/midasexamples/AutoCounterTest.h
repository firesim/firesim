// See LICENSE for license details.

#ifndef MIDASEXAMPLES_AUTOCOUNTERMODULE_H
#define MIDASEXAMPLES_AUTOCOUNTERMODULE_H

#include "TestHarness.h"
#include "bridges/autocounter.h"

class AutoCounterTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  ~AutoCounterTest() override = default;

  const std::vector<autocounter_t *> autocounter_endpoints =
      get_bridges<autocounter_t>();

  void simulation_init() override {
    assert(!autocounter_endpoints.empty() && "missing counters");
    for (auto &autocounter_endpoint : autocounter_endpoints) {
      autocounter_endpoint->init();
    }
  }

  void run_and_collect(int cycles) {
    step(cycles, false);
    while (!peek_poke.is_done()) {
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
