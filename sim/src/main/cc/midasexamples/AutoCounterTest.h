// See LICENSE for license details.

#ifndef MIDASEXAMPLES_AUTOCOUNTERMODULE_H
#define MIDASEXAMPLES_AUTOCOUNTERMODULE_H

#include "TestHarness.h"
#include "bridges/autocounter.h"

class AutoCounterTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  ~AutoCounterTest() override = default;

  void run_and_collect(int cycles) {
    step(cycles, false);
    while (!peek_poke.is_done()) {
      for (auto &autocounter_endpoint : get_bridges<autocounter_t>()) {
        autocounter_endpoint->tick();
      }
    }
  };
};

#endif // MIDASEXAMPLES_AUTOCOUNTERMODULE_H
