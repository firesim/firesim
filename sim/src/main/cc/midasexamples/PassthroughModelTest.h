// See LICENSE for license details.

#ifndef MIDASEXAMPLES_PASSTHROUGHMODELS_H
#define MIDASEXAMPLES_PASSTHROUGHMODELS_H

#include <cstdio>

#include "TestHarness.h"

class PassthroughModelDriver : public TestHarness {
public:
  using TestHarness::TestHarness;

  void add_bridge_driver(synthesized_assertions_t *bridge) override {}

  int latency = 1;
  int length = 1 << 16;
  void run_test() {
    target_reset();
    step(length);
  }
};

#endif MIDASEXAMPLES_PASSTHROUGHMODELS_H
