// See LICENSE for license details.

#include "simif_peek_poke.h"

#include <stdlib.h>
#include <stdio.h>

#include <utility>
#include <map>

constexpr size_t multireg_n_copies = 4;

const size_t multireg_input_ios[multireg_n_copies] =
  { io_pipeIOs_0_i,
    io_pipeIOs_1_i,
    io_pipeIOs_2_i,
    io_pipeIOs_3_i  };

const size_t multireg_output_ios[multireg_n_copies] =
  { io_pipeIOs_0_o,
    io_pipeIOs_1_o,
    io_pipeIOs_2_o,
    io_pipeIOs_3_o  };

uint32_t inputs[] = { 45, 9, 11, 13, 99, 2, 8, 15, 16, 12, 1 };

class MultiReg_t: public simif_peek_poke_t
{
public:
  MultiReg_t(int argc, char** argv) {}

  void run() {
    target_reset();
    for (int i = 0; i < sizeof(inputs) / sizeof(inputs[0]); i++) {
      for (int j = 0; j < multireg_n_copies; j++) {
        poke(multireg_input_ios[j], inputs[i] + j);
        if (i > 0)
          expect(multireg_output_ios[j], inputs[i-1] + j);
        step(1);
      }
    }
  }
};
