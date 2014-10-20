#include "debug_api.h"

class FIR2D_t: debug_api_t
{
public:
  FIR2D_t(int e, int l, int k): debug_api_t("FIR2D", false), element_size(e), line_size(l), kernel_size(k) {}
  void run() {
    poke("FIR2D.io_in_valid", 1);
    poke("FIR2D.io_out_ready", 0);
    for (int i = 0 ; i < 100*((kernel_size-1)*line_size) ; i++) {
      poke("FIR2D.io_in_bits", i % ((kernel_size-1)*line_size));
      step(1);
    } 
  }
private:
  int element_size;
  int line_size;
  int kernel_size;
};

int main() 
{
  FIR2D_t FIR2D(32, 8, 3);
  FIR2D.run();
  return 0;
}
