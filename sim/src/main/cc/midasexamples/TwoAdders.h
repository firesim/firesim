//See LICENSE for license details.

#include "simif.h"
#include "stdio.h"

#define NTESTS 6

class TwoAdders_t: virtual simif_t
{
public:
  TwoAdders_t(int argc, char** argv) {}
  uint32_t i0[NTESTS] = {  4,  8, 13, 26, 19, 0 };
  uint32_t i1[NTESTS] = { 31, 11, 99, 27, 43, 0 };
  uint32_t i2[NTESTS] = { 28,  7, 30,  2, 88, 0 };
  uint32_t i3[NTESTS] = { 67, 29, 50, 80, 59, 0 };
  void run() {
    int i;
    target_reset();
    poke(io_i0, i0[0]);
    poke(io_i1, i1[0]);
    poke(io_i2, i2[0]);
    poke(io_i3, i3[0]);
    step(1);
    for (i = 1; i < NTESTS; i++) {
      poke(io_i0, i0[i]);
      poke(io_i1, i1[i]);
      poke(io_i2, i2[i]);
      poke(io_i3, i3[i]);
      step(1); // has latency of 1 cycle
      expect(io_o0, i0[i-1] + i1[i-1]);
      expect(io_o1, i2[i-1] + i3[i-1]);
    }
  }
};
