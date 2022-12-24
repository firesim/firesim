// See LICENSE for license details.

#include "BridgeHarness.h"

#include "bridges/blockdev.h"
#include "bridges/bridge_driver.h"
#include "bridges/uart.h"

BridgeHarness::BridgeHarness(const std::vector<std::string> &args,
                             simif_t *simif)
    : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create),
      simulation_t(args) {}

BridgeHarness::~BridgeHarness() {}

void BridgeHarness::add_bridge_driver(bridge_driver_t *bridge) {
  bridges.emplace_back(bridge);
}

void BridgeHarness::simulation_init() {
#include "constructor.h"
  for (auto &bridge : bridges) {
    bridge->init();
  }
}

int BridgeHarness::simulation_run() {
  // Reset the device.
  poke(reset, 1);
  step(1);
  poke(reset, 0);
  step(1);

  // Tick until all requests are serviced.
  step(get_step_limit(), false);
  for (unsigned i = 0; i < get_tick_limit() && !simif->done(); ++i) {
    for (auto &bridge : bridges) {
      bridge->tick();
    }
  }

  // Cleanup.
  return teardown();
}

void BridgeHarness::simulation_finish() {
  for (auto &bridge : bridges) {
    bridge->finish();
  }
}
