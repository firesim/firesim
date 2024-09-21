// See LICENSE for license details

#include "bridges/clock.h"
#include "bridges/fased_memory_timing_model.h"
#include "bridges/heartbeat.h"
#include "bridges/peek_poke.h"
#include "core/bridge_driver.h"
#include "core/simif.h"
#include "core/simulation.h"
#include "core/systematic_scheduler.h"

// simulation top-level class. always should extend 'simulation_t' (and implement it's virtual functions).
// it also extends 'systematic_scheduler_t' to provide the '+max-cycles' simulator CLI arg ("plusarg") to terminate a simulation.
class simple_counter_top_t : public systematic_scheduler_t, public simulation_t {
public:
  simple_counter_top_t(simif_t &simif,
                widget_registry_t &registry,
                const std::vector<std::string> &args);

  int simulation_run() override;

  bool simulation_timed_out() override { return !terminated; }

private:
  simif_t &simif;
  /// Reference to the peek-poke bridge.
  peek_poke_t &peek_poke;
  /// Flag to indicate that the simulation was terminated.
  bool terminated = false;
};

simple_counter_top_t::simple_counter_top_t(simif_t &simif,
                             widget_registry_t &registry,
                             const std::vector<std::string> &args)
    : systematic_scheduler_t(args), simulation_t(registry, args), simif(simif),
      peek_poke(registry.get_widget<peek_poke_t>()) {
  // helper to verify that simulation is running
  registry.add_widget(
      new heartbeat_t(simif, registry.get_widget<clockmodule_t>(), args));
}

int simple_counter_top_t::simulation_run() {
  int exit_code = 0;
  // infinite loop until '+max-cycles' value is reached (within 'systematic_scheduler_t')
  while (!terminated && !finished_scheduled_tasks()) {
    // step forward maximum amount of allowable cycles
    peek_poke.step(get_largest_stepsize(), false);
    // while the simulation is running N cycles, run all simulation bridges
    while (!peek_poke.is_done() && !terminated) {
      for (auto *bridge : registry.get_all_bridges()) {
	// do bridge work
        bridge->tick();
	// if a bridge has finished then fully exit
        if (bridge->terminate()) {
          exit_code = bridge->exit_code();
          terminated = true;
          break;
        }
      }
    }
  }
  return exit_code;
}

// used in firesim's 'main' to instantiate the custom C++ class you want for a simulation.
// in this case our 'simple_counter_top_t'
std::unique_ptr<simulation_t>
create_simulation(simif_t &simif,
                  widget_registry_t &registry,
                  const std::vector<std::string> &args) {
  return std::make_unique<simple_counter_top_t>(simif, registry, args);
}
