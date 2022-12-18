#ifndef MIDAEXAMPLES_BRIDGEHARNESS_H
#define MIDAEXAMPLES_BRIDGEHARNESS_H

#include "bridges/blockdev.h"
#include "bridges/peek_poke.h"
#include "bridges/uart.h"
#include "core/simif.h"
#include "core/simulation.h"

class bridge_driver_t;

/**
 * Base class for simple unit tests.
 *
 * All bridges from the DUT are registered, initialised and ticked by this
 * harness for a test-specific number of ticks and steps.
 */
class BridgeHarness : public simulation_t {
public:
  BridgeHarness(const std::vector<std::string> &args, simif_t &sim);

  ~BridgeHarness() override;

  void simulation_init() override;
  int simulation_run() override;
  void simulation_finish() override;

protected:
  virtual unsigned get_step_limit() const = 0;
  virtual unsigned get_tick_limit() const = 0;

private:
  peek_poke_t &peek_poke;
};

#define TEST_MAIN(CLASS_NAME)                                                  \
  std::unique_ptr<simulation_t> create_simulation(                             \
      const std::vector<std::string> &args, simif_t &sim) {                    \
    return std::make_unique<CLASS_NAME>(args, sim);                            \
  }
#endif // MIDAEXAMPLES_BRIDGEHARNESS_H
