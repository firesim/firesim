#ifndef MIDAEXAMPLES_TESTHARNESS_H
#define MIDAEXAMPLES_TESTHARNESS_H

#include "bridges/autocounter.h"
#include "bridges/bridge_driver.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"
#include "simif.h"
#include "simif_peek_poke.h"

/**
 * Base class for simple unit tests.
 *
 * In the constructor, macros for all bridges are invoked to create bridge
 * instances and pass them to an overload of the `add_bridge_driver` function.
 * By default, no bridge is allowed to be registered. If a test case wishes
 * to take control of bridges, it can override the appropriate method and
 * intercept the bridge for later use.
 */
class TestHarness : public simif_peek_poke_t, public simulation_t {
public:
  TestHarness(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create),
        simulation_t(args) {}

  virtual ~TestHarness() {}

  // Bridge creation callbacks.
#define BRIDGE_HANDLER(ty, name)                                               \
  virtual void add_bridge_driver(ty *bridge) {                                 \
    fprintf(stderr, "Cannot handle " name "\n");                               \
    abort();                                                                   \
  }

#ifdef AUTOCOUNTERBRIDGEMODULE_struct_guard
  BRIDGE_HANDLER(autocounter_t, "Auto Counter bridge");
#endif
#ifdef ASSERTBRIDGEMODULE_struct_guard
  BRIDGE_HANDLER(synthesized_assertions_t, "Synthesized Assert bridge");
#endif
#ifdef PRINTBRIDGEMODULE_struct_guard
  BRIDGE_HANDLER(synthesized_prints_t, "Synthesized Print bridge");
#endif
#ifdef RESETPULSEBRIDGEMODULE_struct_guard
  BRIDGE_HANDLER(reset_pulse_t, "Reset Pulse bridge");
#endif
#ifdef PLUSARGSBRIDGEMODULE_struct_guard
  BRIDGE_HANDLER(plusargs_t, "PlusArgs bridge");
#endif
#ifdef TERMINATIONBRIDGEMODULE_struct_guard
  BRIDGE_HANDLER(termination_t, "Termination bridge");
#endif

  /// Test entry point to override.
  virtual void run_test() = 0;

  void simulation_init() override {
#include "constructor.h"
  }

  int simulation_run() override {
    run_test();
    return teardown();
  }
};

#define TEST_MAIN(CLASS_NAME)                                                  \
  std::unique_ptr<simulation_t> create_simulation(                             \
      const std::vector<std::string> &args, simif_t *simif) {                  \
    return std::make_unique<CLASS_NAME>(args, simif);                          \
  }
#endif // MIDAEXAMPLES_TESTHARNESS_H
