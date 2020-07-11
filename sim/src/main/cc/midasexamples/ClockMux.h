//See LICENSE for license details.

#include "simif.h"

class ClockMux_t: virtual simif_t
{
public:
  ClockMux_t(int argc, char** argv) {}
  void run() {
      step(128 * 1024);
    }
};
