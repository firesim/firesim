// See LICENSE for license details.

#include "AutoCounterModule.h"

#ifdef DESIGNNAME_MulticlockAutoCounterModule
class MulticlockAutoCounterModule_t : public autocounter_module_t {
public:
  MulticlockAutoCounterModule_t(const std::vector<std::string> &args,
                                simif_t *simif)
      : autocounter_module_t(args, simif) {}
  virtual void run() {
    for (auto &autocounter_endpoint : autocounter_endpoints) {
      autocounter_endpoint->init();
    }
    poke(reset, 1);
    step(1);
    poke(reset, 0);
    run_and_collect(3000);
  };
};
#endif // DESIGNNAME_MulticlockAutoCounterModule
