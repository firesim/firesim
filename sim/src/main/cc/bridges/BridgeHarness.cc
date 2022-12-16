// See LICENSE for license details.

#include "BridgeHarness.h"

#include "bridges/blockdev.h"
#include "bridges/bridge_driver.h"
#include "bridges/uart.h"

BridgeHarness::BridgeHarness(const std::vector<std::string> &args,
                             simif_t *simif)
    : simulation_t(args), simif(simif) {}

BridgeHarness::~BridgeHarness() {}

void BridgeHarness::add_bridge_driver(bridge_driver_t *bridge) {
  bridges.emplace_back(bridge);
}

void BridgeHarness::add_bridge_driver(peek_poke_t *bridge) {
  peek_poke.reset(bridge);
}

void BridgeHarness::simulation_init() {
#include "constructor.h"
  for (auto &bridge : bridges) {
    bridge->init();
  }
}

int BridgeHarness::simulation_run() {
  // Reset the DUT.
  peek_poke->poke("reset", 1, /*blocking=*/true);
  simif->take_steps(1, /*blocking=*/true);
  peek_poke->poke("reset", 0, /*blocking=*/true);
  simif->take_steps(1, /*blocking=*/true);

  // Tick until all requests are serviced.
  simif->take_steps(get_step_limit(), /*blocking=*/false);
  for (unsigned i = 0; i < get_tick_limit() && !simif->done(); ++i) {
    for (auto &bridge : bridges) {
      bridge->tick();
    }
  }

  // Cleanup.
  return EXIT_SUCCESS;
}

void BridgeHarness::simulation_finish() {
  for (auto &bridge : bridges) {
    bridge->finish();
  }
}
