
#include "simif.h"

class AsyncResetRegister_t: virtual simif_t
{
public:
  AsyncResetRegister_t(int argc, char** argv) {}
  void run() { step(10); }
};
