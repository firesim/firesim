//See LICENSE for license details.

#include "simif.h"

class GCD_t: virtual simif_t
{
public:
  GCD_t(int argc, char** argv) {}
  void run() {
    uint32_t a = 64, b = 48, z = 16; //test vectors
    target_reset();
    do {
      poke(io_a, a);
      poke(io_b, b);
      poke(io_e, cycles() == 0 ? 1 : 0);
      step(1);
    } while (cycles() <= 1 || peek(io_v) == 0);
    expect(io_z, z);
  }
};
