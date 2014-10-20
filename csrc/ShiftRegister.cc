#include "debug_api.h"

class ShiftRegister_t: debug_api_t
{
public:
  ShiftRegister_t(): debug_api_t("ShiftRegister") {}
  void run() {
    std::vector<uint32_t> reg(4, 0);
    for (int i = 0 ; i < 64 ; i++) {
      uint32_t in = rand_next(2);
      poke("ShiftRegister.io_in", in);
      step(1);
      for (int j = 3 ; j > 0 ; j--) {
        reg[j] = reg[j-1];
      }
      reg[0] = in;
      if (t >= 4) expect("ShiftRegister.io_out", reg[3]);
    } 
  }
};

int main() 
{
  ShiftRegister_t ShiftRegister;
  ShiftRegister.run();
  return 0;
}
