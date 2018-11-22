//See LICENSE for license details.

#include "simif.h"

class EnableShiftRegister_t: public virtual simif_t
{
public:
  EnableShiftRegister_t(int argc, char** argv) {}
  void run() {
    target_reset();
    std::vector<uint32_t> reg(4, 0);
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t in    = rand_next(16);
      uint32_t shift = rand_next(2);
      poke(io_in,    in);
      poke(io_shift, shift);
      step(1);
      if (shift) {
        for (int j = 3 ; j > 0 ; j--) reg[j] = reg[j-1];
        reg[0] = in;
      }
      expect(io_out, reg[3]);
    }
  }
};
