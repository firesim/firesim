// See LICENSE for license details.

#include "simif_peek_poke.h"

class Parity_t : public simif_peek_poke_t {
public:
  Parity_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}

  void run() {
    uint32_t is_odd = 0;
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t bit = simif->rand_next(2);
      poke(io_in, bit);
      step(1);
      is_odd = (is_odd + bit) % 2;
      expect(io_out, is_odd);
    }
  }
};
