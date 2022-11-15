// See LICENSE for license details.

#include "bridges/synthesized_assertions.h"
#include "simif_peek_poke.h"

class MulticlockAssertModule_t : public simif_peek_poke_t {
public:
  std::vector<synthesized_assertions_t *> assert_endpoints;
  synthesized_assertions_t *full_rate_assert_ep;
  synthesized_assertions_t *half_rate_assert_ep;
  MulticlockAssertModule_t(int argc, char **argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    ASSERTBRIDGEMODULE_0_substruct_create;
    ASSERTBRIDGEMODULE_1_substruct_create;
    full_rate_assert_ep =
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_0_substruct,
                                     ASSERTBRIDGEMODULE_0_assert_messages);
    half_rate_assert_ep =
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_1_substruct,
                                     ASSERTBRIDGEMODULE_1_assert_messages);
    assert_endpoints.push_back(full_rate_assert_ep);
    assert_endpoints.push_back(half_rate_assert_ep);
  };
  void run() {
    int assertions_thrown = 0;

    for (auto ep : assert_endpoints)
      ep->init();

    poke(reset, 0);
    poke(fullrate_pulseLength, 2);
    poke(fullrate_cycle, 186);
    poke(halfrate_pulseLength, 2);
    poke(halfrate_cycle, 129);
    step(256, false);
    while (!done()) {
      for (auto ep : assert_endpoints) {
        ep->tick();
        if (ep->terminate()) {
          ep->resume();
          assertions_thrown++;
        }
      }
    }
    expect(assertions_thrown == 3, "EXPECT: Two assertions thrown");
  };
};
