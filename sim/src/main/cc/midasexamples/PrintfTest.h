// See LICENSE for license details.

#ifndef MIDASEXAMPLES_PRINTTEST_H
#define MIDASEXAMPLES_PRINTTEST_H

#include "TestHarness.h"
#include "bridges/synthesized_prints.h"

class PrintTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  ~PrintTest() override = default;

  const std::vector<synthesized_prints_t *> print_endpoints =
      get_bridges<synthesized_prints_t>();

  void simulation_init() override {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
  }

  void run_and_collect_prints(int cycles) {
    step(cycles, false);
    while (!peek_poke.is_done()) {
      for (auto &print_endpoint : print_endpoints) {
        print_endpoint->tick();
      }
    }
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->finish();
    }
  }
};

#endif // MIDASEXAMPLES_PRINTTEST_H
