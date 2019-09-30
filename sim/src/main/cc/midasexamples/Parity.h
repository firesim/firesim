//See LICENSE for license details.

#include "simif.h"

class Parity_t: virtual simif_t
{
public:
  Parity_t(int argc, char** argv) {}
  void run() {
    uint32_t is_odd = 0;
    target_reset();
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t bit = rand_next(2);
      poke(io_in, bit);
      step(1);
      is_odd = (is_odd + bit) % 2;
      expect(io_out, is_odd);
    }
  }
};
