// See LICENSE for license details.

#ifndef MIDASEXAMPLES_ASSERTTEST_H
#define MIDASEXAMPLES_ASSERTTEST_H

#include "TestHarness.h"
#include "bridges/synthesized_assertions.h"

class AssertTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  virtual ~AssertTest() {}

  const std::vector<synthesized_assertions_t *> assert_endpoints =
      get_bridges<synthesized_assertions_t>();

  void simulation_init() override {
    for (auto &assert_endpoint : assert_endpoints) {
      assert_endpoint->init();
    }
  }
};

#endif // MIDASEXAMPLES_ASSERTTEST_H
