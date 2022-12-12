// See LICENSE for license details.

#ifndef MIDASEXAMPLES_PRINTFMODULE_H
#define MIDASEXAMPLES_PRINTFMODULE_H

#include <memory>

#include "TestHarness.h"

class PrintTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::vector<std::unique_ptr<synthesized_prints_t>> print_endpoints;
  void add_bridge_driver(synthesized_prints_t *bridge) override {
    print_endpoints.emplace_back(bridge);
  }

  void run_and_collect_prints(int cycles) {
    step(cycles, false);
    while (!simif->done()) {
      for (auto &print_endpoint : print_endpoints) {
        print_endpoint->tick();
      }
    }
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->finish();
    }
  }
};

#endif // MIDASEXAMPLES_PRINTFMODULE_H
