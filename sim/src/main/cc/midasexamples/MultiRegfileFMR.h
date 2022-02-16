// See LICENSE for license details.

#include "simif_peek_poke.h"

#include <stdlib.h>
#include <stdio.h>

#include <utility>

class MultiRegfileFMR_t: public simif_peek_poke_t
{
 public:
  MultiRegfileFMR_t(int argc, char** argv) {}
  void run() {
    target_reset();
    step(10000);
  }
};
