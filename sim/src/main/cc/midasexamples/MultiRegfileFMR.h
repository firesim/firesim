// See LICENSE for license details.

#include "simif.h"

#include <stdlib.h>
#include <stdio.h>

#include <utility>

class MultiRegfileFMR_t: virtual simif_t
{
 public:
  MultiRegfileFMR_t(int argc, char** argv) {}
  void run() {
    target_reset();
    step(10000);
  }
};
