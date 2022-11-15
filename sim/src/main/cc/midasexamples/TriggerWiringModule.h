// See LICENSE for license details.

#include "bridges/synthesized_assertions.h"
#include "simif_peek_poke.h"

class TriggerWiringModule_t : public simif_peek_poke_t {
public:
  std::vector<synthesized_assertions_t *> assert_endpoints;
  TriggerWiringModule_t(int argc, char **argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    ASSERTBRIDGEMODULE_0_substruct_create;
    ASSERTBRIDGEMODULE_1_substruct_create;
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_0_substruct,
                                     ASSERTBRIDGEMODULE_0_assert_messages));
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_1_substruct,
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
    while (!done() && !simulation_complete()) {
      for (auto ep : assert_endpoints) {
        ep->tick();
      }
    }
    expect(!exit_code(), "No assertions should be thrown");
  }
};
