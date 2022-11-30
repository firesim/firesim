
// See LICENSE for license details.

#include "bridges/synthesized_assertions.h"
#include "simif_peek_poke.h"
#include <vector>

class AssertTorture_t : public simif_peek_poke_t {
public:
  std::vector<synthesized_assertions_t *> assert_endpoints;
  AssertTorture_t(int argc, char **argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
#ifdef ASSERTBRIDGEMODULE_0_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_0_substruct_create,
                                     ASSERTBRIDGEMODULE_0_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_1_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_1_substruct_create,
                                     ASSERTBRIDGEMODULE_1_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_2_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_2_substruct_create,
                                     ASSERTBRIDGEMODULE_2_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_3_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_3_substruct_create,
                                     ASSERTBRIDGEMODULE_3_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_4_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_4_substruct_create,
                                     ASSERTBRIDGEMODULE_4_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_5_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_5_substruct_create,
                                     ASSERTBRIDGEMODULE_5_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_6_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_6_substruct_create,
                                     ASSERTBRIDGEMODULE_6_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_7_PRESENT
    assert_endpoints.push_back(
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_7_substruct_create,
                                     ASSERTBRIDGEMODULE_7_assert_messages));
#endif
  };
  void run() {
    for (auto ep : assert_endpoints)
      ep->init();

    target_reset(2);
    step(40000, false);
    while (!done()) {
      for (auto ep : assert_endpoints) {
        ep->tick();
        if (ep->terminate()) {
          ep->resume();
        }
      }
    }
  };
};

class AssertGlobalResetCondition_t : public AssertTorture_t {
public:
  AssertGlobalResetCondition_t(int argc, char **argv)
      : AssertTorture_t(argc, argv){};
  void run() {
    for (auto ep : assert_endpoints)
      ep->init();
    target_reset(2);
    step(40000, false);
    while (!done()) {
      for (auto ep : assert_endpoints) {
        ep->tick();
        if (ep->terminate()) {
          abort();
        }
      }
    }
  };
};
