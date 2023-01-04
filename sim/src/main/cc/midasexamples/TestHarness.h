#ifndef MIDAEXAMPLES_TESTHARNESS_H
#define MIDAEXAMPLES_TESTHARNESS_H

#include <random>

#include "bridges/autocounter.h"
#include "bridges/bridge_driver.h"
#include "bridges/peek_poke.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"

/**
 * Base class for simple unit tests.
 *
 * In the constructor, macros for all bridges are invoked to create bridge
 * instances and pass them to an overload of the `add_bridge_driver` function.
 * By default, no bridge is allowed to be registered. If a test case wishes
 * to take control of bridges, it can override the appropriate method and
 * intercept the bridge for later use.
 */
class TestHarness : public simulation_t {
public:
  TestHarness(const std::vector<std::string> &args, simif_t *simif);

  virtual ~TestHarness() {}

  // Bridge creation callbacks.
#define BRIDGE_HANDLER(ty, name)                                               \
  virtual void add_bridge_driver(ty *bridge) {                                 \
    fprintf(stderr, "Cannot handle " name "\n");                               \
    abort();                                                                   \
  }

  BRIDGE_HANDLER(autocounter_t, "Auto Counter bridge");
  BRIDGE_HANDLER(synthesized_assertions_t, "Synthesized Assert bridge");
  BRIDGE_HANDLER(synthesized_prints_t, "Synthesized Print bridge");
  BRIDGE_HANDLER(reset_pulse_t, "Reset Pulse bridge");
  BRIDGE_HANDLER(plusargs_t, "PlusArgs bridge");
  BRIDGE_HANDLER(termination_t, "Termination bridge");

  void add_bridge_driver(peek_poke_t *bridge) { peek_poke.reset(bridge); }

  /**
   * Test entry point to override.
   */
  virtual void run_test() = 0;

  void simulation_init() override {
#include "constructor.h"
  }

  int simulation_run() override {
    run_test();
    return teardown();
  }

  void step(uint32_t n, bool blocking = true);
  void target_reset(int pulse_length = 5);

  void poke(std::string_view id, uint32_t value, bool blocking = true);
  void poke(std::string_view id, mpz_t &value);

  uint32_t peek(std::string_view id, bool blocking = true);
  void peek(std::string_view id, mpz_t &value);
  uint32_t sample_value(std::string_view id) {
    return peek_poke->sample_value(id);
  }

  /**
   * Returns an upper bound for the cycle reached by the target
   * If using blocking steps, this will be ~equivalent to actual_tcycle()
   */
  uint64_t cycles() { return t; };

  bool expect(std::string_view id, uint32_t expected);
  bool expect(std::string_view id, mpz_t &expected);
  bool expect(bool pass, const char *s);

  int teardown();

protected:
  simif_t *simif;

  /// Random number generator for tests, using a fixed default seed.
  uint64_t random_seed = 0;
  std::mt19937_64 random;

private:
  std::unique_ptr<peek_poke_t> peek_poke;

  bool pass = true;
  bool log = true;

  uint64_t t = 0;
  uint64_t fail_t = 0;
};

#define TEST_MAIN(CLASS_NAME)                                                  \
  std::unique_ptr<simulation_t> create_simulation(                             \
      const std::vector<std::string> &args, simif_t *simif) {                  \
    return std::make_unique<CLASS_NAME>(args, simif);                          \
  }
#endif // MIDAEXAMPLES_TESTHARNESS_H
