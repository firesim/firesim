#include "debug_api.h"

class EnableShiftRegister_t: debug_api_t
{
public:
  EnableShiftRegister_t(): debug_api_t("EnableShiftRegister") {}
  void run() {
    std::vector<uint32_t> reg(4, 0);
    for (int i = 0 ; i < 16 ; i++) {
      uint32_t in    = rand_next(2);
      uint32_t shift = rand_next(2);
      poke("EnableShiftRegister.io_in",    in);
      poke("EnableShiftRegister.io_shift", shift);
      step(1);
      if (shift) {
        for (int j = 3 ; j > 0 ; j--) {
          reg[j] = reg[j-1];
        }
        reg[0] = in;
      }
      expect("EnableShiftRegister.io_out", reg[3]);
    } 
  }
};

int main() 
{
  EnableShiftRegister_t EnableShiftRegister;
  EnableShiftRegister.run();
  return 0;
}
