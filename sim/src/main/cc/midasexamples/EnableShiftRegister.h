//See LICENSE for license details.

#include "simif.h"
#include "shift_register.h"

class EnableShiftRegister_t: public virtual simif_t
{
public:
  EnableShiftRegister_t(int argc, char** argv) {}
  void run() {
    target_reset();
    // Until we re-enable wire-channel initialization on peek poke, need an
    // extra cycle for targetreset to propagate
    step(1);
    std::vector<uint32_t> reg(4, 0);
    ShiftRegister<uint32_t> outputDelayer(2, 0);
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t in    = rand_next(16);
      uint32_t shift = rand_next(2);
      poke(io_in,    in);
      poke(io_shift, shift);
      outputDelayer.enqueue(reg[3]);
      expect(io_out, outputDelayer.current());
      step(1);
      if (shift) {
        for (int j = 3 ; j > 0 ; j--) reg[j] = reg[j-1];
        reg[0] = in;
      }
      outputDelayer.step();
    }
  }
};
