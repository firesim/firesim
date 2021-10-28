//See LICENSE for license details.

#include "simif_peek_poke.h"
#include "stdio.h"

uint32_t i0[] = { 4, 8, 7, 3, 2, 0, 4, 9, 11, 3, 7, 2, 5, 2, 7, 8 };
uint32_t i1[] = { 9, 1, 2, 6, 5, 3, 5, 2,  1, 7, 6, 5, 4, 3, 2, 1 };

class NestedModels_t: public simif_peek_poke_t
{
public:
  NestedModels_t(int argc, char** argv) {}
  int ntests = sizeof(i0) / sizeof(i0[0]);
  int latency = 3;
  void run() {
    int i;
    target_reset();
    for (int i = 0; i < latency; i++) {
      poke(io_a_i0, i0[i]);
      poke(io_a_i1, i1[i]);
      poke(io_b_i0, i1[i]);
      poke(io_b_i1, i0[i]);
      step(1);
    }
    for (i = 0; i < ntests; i++) {
      expect(io_a_o0, i0[i] + 1);
      expect(io_a_o1, i1[i] + 1);
      expect(io_b_o0, i1[i] + 1);
      expect(io_b_o1, i0[i] + 1);
      poke(io_a_i0, (i >= ntests-latency) ? 0 : i0[i+latency]);
      poke(io_a_i1, (i >= ntests-latency) ? 0 : i1[i+latency]);
      poke(io_b_i0, (i >= ntests-latency) ? 0 : i1[i+latency]);
      poke(io_b_i1, (i >= ntests-latency) ? 0 : i0[i+latency]);
      step(1);
     }
    step(10);
  }
};
