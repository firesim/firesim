// See LICENSE for license details.

#include "Risc.h"

class RiscSRAM_t : public Risc_t {
protected:
  RiscSRAM_t(const std::vector<std::string> &args, simif_t *simif)
      : Risc_t(args, simif) {}

  virtual void init_app(app_t &app) {
    expected = 40;
    timeout = 400;
    long_app(app);
  }
};
