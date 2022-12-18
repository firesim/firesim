// See LICENSE for license details.

#ifndef MIDASEXAMPLES_PASSTHROUGHMODELS_H
#define MIDASEXAMPLES_PASSTHROUGHMODELS_H

#include <cstdio>

#include "TestHarness.h"

class PassthroughModelDriver : public TestHarness {
public:
  using TestHarness::TestHarness;

  int latency = 1;
  int length = 1 << 16;

  void run_test() override {
    target_reset();
    step(length);
  }
};

#endif // MIDASEXAMPLES_PASSTHROUGHMODELS_H
