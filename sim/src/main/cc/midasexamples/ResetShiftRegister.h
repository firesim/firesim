//See LICENSE for license details.

#include "simif.h"
#include "shift_register.h"

class ResetShiftRegister_t: public virtual simif_t
{
public:
  ResetShiftRegister_t(int argc, char** argv) {}
  void run() {
    std::vector<uint32_t> ins(5, 0);
    int k = 0;
    target_reset();
    // Until we re-enable wire-channel initialization on peek poke, need an
    // extra cycle for targetreset to propagate
    step(1);
    ShiftRegister<uint32_t> outputDelayer(2, 0);
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t in    = rand_next(16);
      uint32_t shift = rand_next(2);
      if (shift == 1) ins[k % 5] = in;
      poke(io_in,    in);
      poke(io_shift, shift);
      outputDelayer.enqueue(cycles() < 3 ? 0 : ins[(k + 1) % 5]);
      expect(io_out, outputDelayer.current());
      step(1);
      outputDelayer.step();
      if (shift == 1) k++;
    }
  }
};
