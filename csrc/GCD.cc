#include "debug_api.h"
#include <assert.h>
#include <iostream>

class GCD_t: debug_api_t
{
public:
  GCD_t(): debug_api_t("GCD") {}
  void run() {
    uint32_t a = 64, b = 48, z = 16; //test vectors
    do {
      uint32_t first = 0;
      if (t == 0) first = 1;
      poke("GCD.io_a", a);
      poke("GCD.io_b", b);
      poke("GCD.io_e", first);
      step(1);
    } while (t <= 1 || peek("GCD.io_v") == 0);
    expect("GCD.io_z", z);
  }
};

int main() 
{
  GCD_t GCD;
  GCD.run();
  return 0;
}
