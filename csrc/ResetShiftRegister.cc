#include "debug_api.h"

class ResetShiftRegister_t: debug_api_t
{
public:
  ResetShiftRegister_t(): debug_api_t("ResetShiftRegister") {}
  void run() {
    std::vector<uint32_t> ins(4, 0);
    int k = 0;
    for (int i = 0 ; i < 16 ; i++) {
      uint32_t in    = rand_next(2);
      uint32_t shift = rand_next(2);
      if (shift == 1)
        ins[k % 5] = in;
      poke("ResetShiftRegister.io_in",    in);
      poke("ResetShiftRegister.io_shift", shift);
      step(1);
      if (shift)
        k++;
      int expected = 0;
      if (t > 4) expected = ins[(k + 1) % 4];
      expect("ResetShiftRegister.io_out", expected);
    } 
  }
};

int main() 
{
  ResetShiftRegister_t ResetShiftRegister;
  ResetShiftRegister.run();
  return 0;
}
