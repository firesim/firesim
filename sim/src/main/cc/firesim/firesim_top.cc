// See LICENSE for license details

#include "bridges/autocounter.h"
#include "bridges/blockdev.h"
#include "bridges/dromajo.h"
#include "bridges/fased_memory_timing_model.h"
#include "bridges/fpga_model.h"
#include "bridges/groundtest.h"
#include "bridges/heartbeat.h"
#include "bridges/peek_poke.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/serial.h"
#include "bridges/simplenic.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"
#include "bridges/tracerv.h"
#include "bridges/uart.h"
#include "core/bridge_driver.h"
#include "core/simif.h"
#include "core/simulation.h"
#include "core/systematic_scheduler.h"

class firesim_top_t : public systematic_scheduler_t, public simulation_t {
public:
  firesim_top_t(const std::vector<std::string> &args, simif_t *simif);
  ~firesim_top_t() {}

  void simulation_init();
  void simulation_finish();
  int simulation_run();

protected:
  void add_bridge_driver(peek_poke_t *peek_poke) { delete peek_poke; }
  void add_bridge_driver(bridge_driver_t *bridge) {
    bridges.emplace_back(bridge);
  }
  void add_bridge_driver(FpgaModel *bridge) {
    fpga_models.emplace_back(bridge);
  }

private:
  // Simulator interface.
  simif_t *simif;

  // A registry of all bridge drivers in the simulator
  std::vector<std::unique_ptr<bridge_driver_t>> bridges;
  // FPGA-hosted models with programmable registers & instrumentation
  // (i.e., bridges_drivers whose tick() is a nop)
  std::vector<std::unique_ptr<FpgaModel>> fpga_models;

  // profile interval: # of cycles to advance before profiling instrumentation
  // registers in models
  uint64_t profile_interval = -1;
  uint64_t profile_models();

  // Returns true if any bridge has signaled for simulation termination
  bool simulation_complete();
  // Returns the error code of the first bridge for which it is non-zero
  int exit_code();
};

firesim_top_t::firesim_top_t(const std::vector<std::string> &args,
                             simif_t *simif)
    : simulation_t(*simif, args), simif(simif) {
  max_cycles = -1;
  profile_interval = max_cycles;

  for (auto &arg : args) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atoi(arg.c_str() + 12);
    }
    if (arg.find("+profile-interval=") == 0) {
      profile_interval = atoi(arg.c_str() + 18);
    }
  }
}

bool firesim_top_t::simulation_complete() {
  bool is_complete = false;
  for (auto &e : bridges) {
    is_complete |= e->terminate();
  }
  return is_complete;
}

uint64_t firesim_top_t::profile_models() {
  for (auto &mod : fpga_models) {
    mod->profile();
  }
  return profile_interval;
}

int firesim_top_t::exit_code() {
  for (auto &e : bridges) {
    if (e->exit_code())
      return e->exit_code();
  }
  return 0;
}

void firesim_top_t::simulation_init() {
  add_bridge_driver(new heartbeat_t(simif, args));

  // DOC include start: Bridge Driver Registration
  // Here we instantiate our driver once for each bridge in the target
  // Golden Gate emits a <BridgeModuleClassName>_<id>_PRESENT macro for each
  // instance which you may use to conditionally instantiate your driver.
  // This file can be included in the setup method of any top-level to pass
  // an instance of each driver to the `add_bridge_driver` method. Drivers can
  // be distinguished by overloading the method with the appropriate type.
#include "core/constructor.h"
  // DOC include end: Bridge Driver Registration

  // Add functions you'd like to periodically invoke on a paused simulator here.
  if (profile_interval != -1) {
    register_task([this]() { return this->profile_models(); }, 0);
  }

  for (auto &e : fpga_models) {
    e->init();
  }

  for (auto &e : bridges) {
    e->init();
  }
}

int firesim_top_t::simulation_run() {

  while (!simulation_complete() && !finished_scheduled_tasks()) {
    run_scheduled_tasks();
    simif->take_steps(get_largest_stepsize(), false);
    while (!simif->done() && !simulation_complete()) {
      for (auto &e : bridges)
        e->tick();
    }
  }

  return exit_code();
}

void firesim_top_t::simulation_finish() {
  for (auto &e : fpga_models) {
    e->finish();
  }

  for (auto &e : bridges) {
    e->finish();
  }
}

std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t *simif) {
  return std::make_unique<firesim_top_t>(args, simif);
}
