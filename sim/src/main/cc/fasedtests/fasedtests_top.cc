// See LICENSE for license details.

#include "bridges/fased_memory_timing_model.h"
#include "bridges/fpga_model.h"
#include "bridges/peek_poke.h"
#include "bridges/reset_pulse.h"
#include "core/bridge_driver.h"
#include "core/simulation.h"
#include "core/systematic_scheduler.h"
#include "test_harness_bridge.h"

class fasedtests_top_t : public systematic_scheduler_t, public simulation_t {
public:
  fasedtests_top_t(const std::vector<std::string> &args, simif_t *simif);
  ~fasedtests_top_t() override = default;

  void simulation_init() override;
  void simulation_finish() override;
  int simulation_run() override;

protected:
  void add_bridge_driver(bridge_driver_t *bridge) {
    bridges.emplace_back(bridge);
  }
  void add_bridge_driver(FpgaModel *bridge) {
    fpga_models.emplace_back(bridge);
  }
  void add_bridge_driver(peek_poke_t *bridge) { peek_poke.reset(bridge); }

private:
  // Simulation interface.
  simif_t *simif;
  // Peek-poke bridge.
  std::unique_ptr<peek_poke_t> peek_poke;
  // Memory mapped bridges bound to software models
  std::vector<std::unique_ptr<bridge_driver_t>> bridges;
  // FPGA-hosted models with programmable registers & instrumentation
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

fasedtests_top_t::fasedtests_top_t(const std::vector<std::string> &args,
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

bool fasedtests_top_t::simulation_complete() {
  bool is_complete = false;
  for (auto &e : bridges) {
    is_complete |= e->terminate();
  }
  return is_complete;
}

uint64_t fasedtests_top_t::profile_models() {
  for (auto &mod : fpga_models) {
    mod->profile();
  }
  return profile_interval;
}

int fasedtests_top_t::exit_code() {
  for (auto &e : bridges) {
    if (e->exit_code())
      return e->exit_code();
  }
  return 0;
}

void fasedtests_top_t::simulation_init() {
#include "core/constructor.h"
  // Add functions you'd like to periodically invoke on a paused simulator here.
  if (profile_interval != -1) {
    register_task([this]() { return this->profile_models(); }, 0);
  }

  // Test harness.
  add_bridge_driver(new test_harness_bridge_t(
      *simif, *peek_poke, fpga_models[0]->get_addr_map(), args));

  for (auto &e : fpga_models) {
    e->init();
  }

  for (auto &e : bridges) {
    e->init();
  }
}

int fasedtests_top_t::simulation_run() {
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

void fasedtests_top_t::simulation_finish() {
  for (auto &e : fpga_models) {
    e->finish();
  }

  for (auto &e : bridges) {
    e->finish();
  }
}

std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t *simif) {
  return std::make_unique<fasedtests_top_t>(args, simif);
}
