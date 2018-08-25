//See LICENSE for license details.

#include "Risc.h"

class RiscSRAM_t: public Risc_t
{
protected:

  RiscSRAM_t(int argc, char** argv) : Risc_t(argc, argv) {}
  virtual void init_app(app_t& app) {
    expected = 40;
    timeout = 400;
    long_app(app);
  }
};
