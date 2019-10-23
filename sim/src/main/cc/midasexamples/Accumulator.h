//See LICENSE for license details.

#include "simif.h"

class Accumulator_t: virtual simif_t
{
public:
  Accumulator_t(int argc, char** argv) {}
  void run() {
    uint32_t inputs[] = {4, 18, 9, 77, 5, 3};
    uint32_t i, total = 0;
    target_reset();
    for (i = 0; i < (sizeof(inputs) / sizeof(inputs[0])); i++) {
      poke(io_in, inputs[i]);
      step(1);
      total += inputs[i];
      expect(io_out, total);
    }
  }
};
