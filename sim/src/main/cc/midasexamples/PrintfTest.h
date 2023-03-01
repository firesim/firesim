// See LICENSE for license details.

#ifndef MIDASEXAMPLES_PRINTTEST_H
#define MIDASEXAMPLES_PRINTTEST_H

#include "TestHarness.h"
#include "bridges/synthesized_prints.h"

class PrintTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  ~PrintTest() override = default;

  void run_and_collect_prints(int cycles) {
    step(cycles, false);
    while (!peek_poke.is_done()) {
      for (auto &print_endpoint : get_bridges<synthesized_prints_t>()) {
        print_endpoint->tick();
      }
    }
  }
};

#endif // MIDASEXAMPLES_PRINTTEST_H
