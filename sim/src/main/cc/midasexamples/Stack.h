// See LICENSE for license details.

#include "simif_peek_poke.h"
#include <stack>

class Stack_t : public simif_peek_poke_t {
public:
  Stack_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}

  void run() {
    std::stack<uint32_t> stack;
    uint32_t nextDataOut = 0;
    target_reset();
    for (int i = 0; i < 64; i++) {
      uint32_t enable = simif->rand_next(2);
      uint32_t push = simif->rand_next(2);
      uint32_t pop = simif->rand_next(2);
      uint32_t dataIn = simif->rand_next(256);
      uint32_t dataOut = nextDataOut;

      if (enable) {
        if (stack.size())
          nextDataOut = stack.top();
        if (push && stack.size() < size) {
          stack.push(dataIn);
        } else if (pop && stack.size() > 0) {
          stack.pop();
        }
      }
      poke(io_pop, pop);
      poke(io_push, push);
      poke(io_en, enable);
      poke(io_dataIn, dataIn);
      expect(io_dataOut, dataOut);
      step(1);
    }
  }

private:
  const size_t size = 64;
};
