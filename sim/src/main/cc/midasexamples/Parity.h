//See LICENSE for license details.

#include "simif.h"
#include "shift_register.h"

class Parity_t: virtual simif_t
{
public:
  Parity_t(int argc, char** argv) {}
  void run() {
    uint32_t is_odd = 0;
    ShiftRegister<uint32_t> outputDelayer(2, 0);
    target_reset();
    step(1);
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t bit = rand_next(2);
      poke(io_in, bit);
      outputDelayer.enqueue(is_odd);
      expect(io_out, outputDelayer.current());
      step(1);
      is_odd = (is_odd + bit) % 2;
      outputDelayer.step();
    }
  }
};
