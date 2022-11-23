// See LICENSE for license details.

#include <memory>

#include "AutoCounterModule.h"
#include "simif_peek_poke.h"

#ifdef DESIGNNAME_AutoCounterCoverModule
class AutoCounterCoverModule_t : public autocounter_module_t {
public:
  AutoCounterCoverModule_t(const std::vector<std::string> &args, simif_t *simif)
      : autocounter_module_t(args, simif) {}

  virtual void run() {
    for (auto &autocounter_endpoint : autocounter_endpoints) {
      autocounter_endpoint->init();
    }
    poke(reset, 1);
    poke(io_a, 0);
    step(1);
    poke(reset, 0);
    step(1);
    poke(io_a, 1);
    run_and_collect(3000);
  };
};
#endif // DESIGNNAME_AutoCounterCoverModule
