// See LICENSE for license details.

#ifndef RTLSIM
#include "simif_f1.h"
#define SIMIF simif_f1_t
#else
#include "simif_emul.h"
#define SIMIF simif_emul_t
#endif

#include "bridges/tracerv.h"
#include <random>

#include "bridges/autocounter.h"
#include "bridges/bridge_driver.h"
#include "bridges/peek_poke.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/termination.h"

#include "TestHarness.h"

#include <iostream>

class TracerVModule final : public simulation_t {
public:
  TracerVModule(const std::vector<std::string> &args, simif_t *simif)
      : simulation_t(*simif, args), simif(simif) {
    std::cout << "TracerVModule()\n";
  }

  virtual ~TracerVModule() {}

  void add_bridge_driver(bridge_driver_t *bridge) {
    bridges.emplace_back(bridge);
  }

  void add_bridge_driver(peek_poke_t *bridge) { peek_poke.reset(bridge); }

  void add_bridge_driver(tracerv_t *bridge) {
    std::cout << "tracerv::add_bridge_driver(tracerv)\n";
    assert(!tracerv && "multiple bridges registered");
    tracerv.reset(bridge);
  }

  void simulation_init() override {
    std::cout << "simulation_init\n";
#include "constructor.h"
    for (auto &bridge : bridges) {
      std::cout << "init bridge\n";
      bridge->init();
    }
  }
  int simulation_run() override {
    std::cout << "simulation_run\n";
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
  void simulation_finish() override {
    for (auto &bridge : bridges) {
      bridge->finish();
    }
  }

private:
  simif_t *simif;
  std::vector<std::unique_ptr<bridge_driver_t>> bridges;
  std::unique_ptr<peek_poke_t> peek_poke;

  unsigned get_step_limit() const { return 6000; }
  unsigned get_tick_limit() const { return 3000; }

  std::unique_ptr<tracerv_t> tracerv;
};

TEST_MAIN(TracerVModule)

// file
// create file called TracerVModule.cc
// in simulation_run() do my stuff
// override add_Bridge_driver like
// /home/centos/firesim/sim/src/main/cc/midasexamples/TestAssertModule.cc
