// See LICENSE for license details.

#include "TestHarness.h"

static const uint32_t i0[] = {4, 8, 7, 3, 2, 0, 4, 9, 11, 3, 7, 2, 5, 2, 7, 8};
static const uint32_t i1[] = {9, 1, 2, 6, 5, 3, 5, 2, 1, 7, 6, 5, 4, 3, 2, 1};
static constexpr int ntests = sizeof(i0) / sizeof(i0[0]);

class TestNestedModels : public TestHarness {
public:
  using TestHarness::TestHarness;

  int latency = 3;

  void run_test() {
    target_reset();
    for (int i = 0; i < latency; i++) {
      poke("io_a_i0", i0[i]);
      poke("io_a_i1", i1[i]);
      poke("io_b_i0", i1[i]);
      poke("io_b_i1", i0[i]);
      step(1);
    }
    for (int i = 0; i < ntests; i++) {
      expect("io_a_o0", i0[i] + 1);
      expect("io_a_o1", i1[i] + 1);
      expect("io_b_o0", i1[i] + 1);
      expect("io_b_o1", i0[i] + 1);
      poke("io_a_i0", (i >= ntests - latency) ? 0 : i0[i + latency]);
      poke("io_a_i1", (i >= ntests - latency) ? 0 : i1[i + latency]);
      poke("io_b_i0", (i >= ntests - latency) ? 0 : i1[i + latency]);
      poke("io_b_i1", (i >= ntests - latency) ? 0 : i0[i + latency]);
      step(1);
    }
    step(10);
  }
};

TEST_MAIN(TestNestedModels)
