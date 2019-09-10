//See LICENSE for license details.

#include "simif.h"
#include "shift_register.h"

class ShiftRegister_t: virtual simif_t
{
public:
  ShiftRegister_t(int argc, char** argv) {}
  void run() {
    std::vector<uint32_t> reg(4);
    target_reset();
    ShiftRegister<uint32_t> outputDelayer(2, 0);
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t in = rand_next(2);
      poke(io_in, in);
      outputDelayer.enqueue(reg[3]);
      if (cycles() > 4) expect(io_out, outputDelayer.current());
      step(1);
      outputDelayer.step();
      for (int j = 3 ; j > 0 ; j--) reg[j] = reg[j-1];
      reg[0] = in;
    }
  }
};
