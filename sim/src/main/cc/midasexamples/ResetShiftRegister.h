// See LICENSE for license details.

#include "simif_peek_poke.h"

class ResetShiftRegister_t : public simif_peek_poke_t {
public:
  ResetShiftRegister_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}
  void run() {
    std::vector<uint32_t> ins(5, 0);
    int k = 0;
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t in = simif->rand_next(16);
      uint32_t shift = simif->rand_next(2);
      if (shift == 1)
        ins[k % 5] = in;
      poke(io_in, in);
      poke(io_shift, shift);
      step(1);
      if (shift == 1)
        k++;
      expect(io_out, cycles() < 3 ? 0 : ins[(k + 1) % 5]);
    }
  }
};
