#include "debug_api.h"

class Parity_t: debug_api_t
{
public:
  Parity_t(): debug_api_t("Parity") {}
  void run() {
    uint32_t isOdd = 0; 
    for (int i = 0 ; i < 10 ; i++) {
      uint32_t bit = rand_next(2);
      poke("Parity.io_in", bit);
      step(1);
      isOdd = (isOdd + bit) % 2;
      expect("Parity.io_out", isOdd);
    } 
  }
};

int main() 
{
  Parity_t Parity;
  Parity.run();
  return 0;
}
