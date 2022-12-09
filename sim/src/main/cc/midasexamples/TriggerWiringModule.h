// See LICENSE for license details.

#include "bridges/synthesized_assertions.h"
#include "simif_peek_poke.h"

class TriggerWiringModule_t : public simif_peek_poke_t {
public:
  std::vector<synthesized_assertions_t *> assert_endpoints;
  TriggerWiringModule_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {
    assert_endpoints.push_back(
        new synthesized_assertions_t(simif,
                                     args,
                                     ASSERTBRIDGEMODULE_0_substruct_create,
                                     ASSERTBRIDGEMODULE_0_assert_messages));
    assert_endpoints.push_back(
        new synthesized_assertions_t(simif,
                                     args,
                                     ASSERTBRIDGEMODULE_1_substruct_create,
                                     ASSERTBRIDGEMODULE_1_assert_messages));
  };
  bool simulation_complete() {
    bool is_complete = false;
    for (auto &e : assert_endpoints) {
      is_complete |= e->terminate();
    }
    return is_complete;
  }
  int exit_code() {
    for (auto &e : assert_endpoints) {
      if (e->exit_code())
        return e->exit_code();
    }
    return 0;
  }
  void run() {
    int assertions_thrown = 0;

    for (auto ep : assert_endpoints)
      ep->init();

    poke(reset, 1);
    step(1);
    poke(reset, 0);
    step(10000, false);
    while (!simif->done() && !simulation_complete()) {
      for (auto ep : assert_endpoints) {
        ep->tick();
      }
    }
    expect(!exit_code(), "No assertions should be thrown");
  }
};
