#ifndef MIDAEXAMPLES_TESTHARNESS_H
#define MIDAEXAMPLES_TESTHARNESS_H

#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

#include "bridges/autocounter.h"
#include "bridges/bridge_driver.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"

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
class TestHarness : public SIMIF, public simif_peek_poke_t {
public:
  TestHarness(const std::vector<std::string> &args)
      : SIMIF(args),
        simif_peek_poke_t(this, PEEKPOKEBRIDGEMODULE_0_substruct_create) {}

  virtual ~TestHarness() {}

  /// Initialization method that registers all bridges.
  void add_bridges(const std::vector<std::string> &args) {
#include "constructor.h"
  }

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
};

#define TEST_MAIN(CLASS_NAME)                                                  \
  int main(int argc, char **argv) {                                            \
    std::vector<std::string> args(argv + 1, argv + argc);                      \
    CLASS_NAME dut(args);                                                      \
    dut.add_bridges(args);                                                     \
    dut.init(argc, argv);                                                      \
    dut.run_test();                                                            \
    return dut.teardown();                                                     \
  }

#endif // MIDAEXAMPLES_TESTHARNESS_H
