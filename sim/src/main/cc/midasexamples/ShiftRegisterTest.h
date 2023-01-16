// See LICENSE for license details.

#ifndef MIDASEXAMPLES_SHIFTREGISTER_H
#define MIDASEXAMPLES_SHIFTREGISTER_H

#include "TestHarness.h"

class ShiftRegisterTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() {
    std::vector<uint32_t> reg(4);
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t in = random() % 2;
      poke("io_in", in);
      step(1);
      for (int j = 3; j > 0; j--)
        reg[j] = reg[j - 1];
      reg[0] = in;
      if (cycles() > 4)
        expect("io_out", reg[3]);
    }
  }
};

#endif // MIDASEXAMPLES_SHIFTREGISTER_H
