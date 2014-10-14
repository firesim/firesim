#include "debug_api.h"
#include <assert.h>
#include <iostream>

int main() 
{
  debug_api_t api("GCD");
  
  // Tests
  uint32_t a = 64, b = 48, z = 16; //test vectors
  uint32_t peek_v; // output values
  int t = 0;
  do {
    uint32_t first = 0;
    if (api.cycle() == 0) first = 1;
    api.poke("GCD.io_a", a);
    api.poke("GCD.io_b", b);
    api.poke("GCD.io_e", first);
    api.step(1);
  } while (api.cycle() <= 1 || api.peek("GCD.io_v") == 0);
  api.expect("GCD.io_z", z);

  return 0;
}
