// See LICENSE for license details.

#ifndef MIDASEXAMPLES_ASSERTTEST_H
#define MIDASEXAMPLES_ASSERTTEST_H

#include "TestHarness.h"
#include "bridges/synthesized_assertions.h"

class AssertTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  ~AssertTest() override = default;

  std::vector<synthesized_assertions_t *> assert_endpoints =
      get_bridges<synthesized_assertions_t>();

  void simulation_init() override {
    TestHarness::simulation_init();
    for (auto *assert_endpoint : assert_endpoints) {
      assert_endpoint->init();
    }
  }
};

#endif // MIDASEXAMPLES_ASSERTTEST_H
