// See LICENSE for license details.

#include "simif_peek_poke.h"

class EnableShiftRegister_t : public simif_peek_poke_t {
public:
  EnableShiftRegister_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}
  void run() {
    target_reset();
    std::vector<uint32_t> reg(4, 0);
    for (int i = 0; i < 64; i++) {
      uint32_t in = simif->rand_next(16);
      uint32_t shift = simif->rand_next(2);
      poke(io_in, in);
      poke(io_shift, shift);
      step(1);
      if (shift) {
        for (int j = 3; j > 0; j--)
          reg[j] = reg[j - 1];
        reg[0] = in;
      }
      expect(io_out, reg[3]);
    }
  }
};
