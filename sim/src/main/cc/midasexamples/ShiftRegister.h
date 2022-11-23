// See LICENSE for license details.

#include "simif_peek_poke.h"

class ShiftRegister_t : public simif_peek_poke_t {
public:
  ShiftRegister_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}

  void run() {
    std::vector<uint32_t> reg(4);
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t in = simif->rand_next(2);
      poke(io_in, in);
      step(1);
      for (int j = 3; j > 0; j--)
        reg[j] = reg[j - 1];
      reg[0] = in;
      if (cycles() > 4)
        expect(io_out, reg[3]);
    }
  }
};
