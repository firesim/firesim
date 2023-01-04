// See LICENSE for license details

#include "bridges/clock.h"
#include "bridges/fased_memory_timing_model.h"
#include "bridges/heartbeat.h"
#include "bridges/peek_poke.h"
#include "core/bridge_driver.h"
#include "core/simif.h"
#include "core/simulation.h"
#include "core/systematic_scheduler.h"

class firesim_top_t : public systematic_scheduler_t, public simulation_t {
public:
  firesim_top_t(simif_t &simif,
                widget_registry_t &registry,
                const std::vector<std::string> &args);
  ~firesim_top_t() override = default;

  void simulation_init() override;
  void simulation_finish() override;
  int simulation_run() override;

  bool simulation_timed_out() override {
    return !simulation_complete() && peek_poke.is_done() &&
           finished_scheduled_tasks();
  }

private:
  // Reference to the peek-poke bridge.
  peek_poke_t &peek_poke;
  // profile interval: # of cycles to advance before profiling instrumentation
  // registers in models
  std::optional<uint64_t> profile_interval;
  uint64_t profile_models();

  // Returns true if any bridge has signaled for simulation termination
  bool simulation_complete();
  // Returns the error code of the first bridge for which it is non-zero
  int exit_code();
};

firesim_top_t::firesim_top_t(simif_t &simif,
                             widget_registry_t &registry,
                             const std::vector<std::string> &args)
    : simulation_t(registry, args),
      peek_poke(registry.get_widget<peek_poke_t>()) {

  max_cycles = -1;

  for (auto &arg : args) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atoi(arg.c_str() + 12);
    }
    if (arg.find("+profile-interval=") == 0) {
      profile_interval = atoi(arg.c_str() + 18);
    }
  }

  registry.add_widget(
      new heartbeat_t(simif, registry.get_widget<clockmodule_t>(), args));
}

bool firesim_top_t::simulation_complete() {
  bool is_complete = false;
  for (auto &e : registry.get_all_bridges()) {
    is_complete |= e->terminate();
  }
  return is_complete;
}

uint64_t firesim_top_t::profile_models() {
  for (auto &mod : registry.get_all_models()) {
    mod->profile();
  }
  return *profile_interval;
}

int firesim_top_t::exit_code() {
  for (auto &e : registry.get_all_bridges()) {
    if (e->exit_code())
      return e->exit_code();
  }
  return 0;
}

void firesim_top_t::simulation_init() {
  // Add functions you'd like to periodically invoke on a paused simulator here.
  if (profile_interval.has_value()) {
    register_task([this]() { return this->profile_models(); }, 0);
  }

  for (auto *bridge : registry.get_all_bridges()) {
    bridge->init();
  }
  for (auto *model : registry.get_all_models()) {
    model->init();
  }
}

int firesim_top_t::simulation_run() {
  while (!simulation_complete() && !finished_scheduled_tasks()) {
    run_scheduled_tasks();
    peek_poke.step(get_largest_stepsize(), false);
    while (!peek_poke.is_done() && !simulation_complete()) {
      for (auto &e : registry.get_all_bridges())
        e->tick();
    }
  }

  return exit_code();
}

void firesim_top_t::simulation_finish() {
  for (auto *bridge : registry.get_all_bridges()) {
    bridge->finish();
  }
  for (auto *model : registry.get_all_models()) {
    model->finish();
  }
}

std::unique_ptr<simulation_t>
create_simulation(simif_t &simif,
                  widget_registry_t &registry,
                  const std::vector<std::string> &args) {
  return std::make_unique<firesim_top_t>(simif, registry, args);
}
