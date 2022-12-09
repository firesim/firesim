// See LICENSE for license details.

#include "simif_peek_poke.h"

#include <stdio.h>
#include <stdlib.h>

#include <utility>

class MultiRegfileFMR_t : public simif_peek_poke_t {
public:
  MultiRegfileFMR_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}

  void run() {
    target_reset();
    step(10000);
  }
};
