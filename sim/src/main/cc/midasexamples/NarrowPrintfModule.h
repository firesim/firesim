// See LICENSE for license details.

#include "PrintfModule.h"
class NarrowPrintfModule_t : public print_module_t {
public:
  NarrowPrintfModule_t(const std::vector<std::string> &args, simif_t *simif)
      : print_module_t(args, simif) {}

  virtual void run() {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    poke(reset, 1);
    poke(io_enable, 0);
    step(1);
    poke(io_enable, 1);
    poke(reset, 0);
    step(4);
    // Test idle-cycle rollover
    poke(io_enable, 0);
    step(256);
    poke(io_enable, 1);
    run_and_collect_prints(256);
  };
};
