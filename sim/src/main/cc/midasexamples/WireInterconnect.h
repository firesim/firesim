//See LICENSE for license details.

#include "simif.h"

class WireInterconnect_t: public virtual simif_t
{
public:
  WireInterconnect_t(int argc, char** argv) {}
  void run() {
    target_reset();
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t in    = rand_next(16);
      poke(aIn,    in);
      expect(aOut, in);
      step(1);
    }
  }
};
