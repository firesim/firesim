// See LICENSE for license details.

#include "PrintfModule.h"

#ifdef DESIGNNAME_MulticlockPrintfModule
class MulticlockPrintfModule_t : public print_module_t {
public:
  MulticlockPrintfModule_t(const std::vector<std::string> &args, simif_t *simif)
      : print_module_t(args, simif) {}

  virtual void run() {
    for (auto &print_endpoint : print_endpoints) {
      print_endpoint->init();
    }
    step(1);
    poke(reset, 0);
    run_and_collect_prints(256);
  };
};
#endif // DESIGNNAME_MulticlockPrintfModule
