// See LICENSE for license details.

#include "bridges/clock.h"
#include "bridges/fased_memory_timing_model.h"
#include "bridges/peek_poke.h"
#include "bridges/reset_pulse.h"
#include "core/bridge_driver.h"
#include "core/simulation.h"
#include "core/systematic_scheduler.h"

class fasedtests_top_t : public systematic_scheduler_t, public simulation_t {
public:
  fasedtests_top_t(simif_t &simif,
                   widget_registry_t &registry,
                   const std::vector<std::string> &args);

  int simulation_run() override;

  bool simulation_timed_out() override { return !done; }

private:
  /// Reference to the simulation interface.
  simif_t &simif;
  /// Reference to the peek-poke bridge.
  peek_poke_t &peek_poke;
  /// List of models in the design.
  std::vector<FASEDMemoryTimingModel *> models;
  /// Records all uarch events we want to validate.
  std::unordered_map<std::string, uint32_t> expected_uarchevent_values;
  /// Flag to indicating that the target reported it is done.
  bool done = false;
};

fasedtests_top_t::fasedtests_top_t(simif_t &simif,
                                   widget_registry_t &registry,
                                   const std::vector<std::string> &args)
    : systematic_scheduler_t(args), simulation_t(registry, args), simif(simif),
      peek_poke(registry.get_widget<peek_poke_t>()),
      models(registry.get_bridges<FASEDMemoryTimingModel>()) {

  // Cycles to advance before profiling instrumentation registers in models.
  std::optional<uint64_t> profile_interval;
  for (auto &arg : args) {
    if (arg.find("+profile-interval=") == 0) {
      profile_interval = atoi(arg.c_str() + 18);
      continue;
    }
    if (arg.find("+expect_") == 0) {
      auto sub_arg = std::string(arg.c_str() + 8);
      size_t delimit_idx = sub_arg.find_first_of("=");
      std::string key = sub_arg.substr(0, delimit_idx).c_str();
      int value = std::stoi(sub_arg.substr(delimit_idx + 1).c_str());
      expected_uarchevent_values[key] = value;
      continue;
    }
  }

  // Add functions you'd like to periodically invoke on a paused simulator here.
  if (profile_interval) {
    register_task(0, [&] {
      for (auto *mod : registry.get_bridges<FASEDMemoryTimingModel>()) {
        mod->profile();
      }
      return *profile_interval;
    });
  }
}

int fasedtests_top_t::simulation_run() {
  // Run the simulation until the target reports it is finished by setting
  // the done output flag or the desired number of cycles completes.
  while (!done && !finished_scheduled_tasks()) {
    run_scheduled_tasks();
    peek_poke.step(get_largest_stepsize(), false);
    while (!peek_poke.is_done() && !done) {
      for (auto *bridge : registry.get_all_bridges()) {
        bridge->tick();
      }
      if (peek_poke.sample_value("done")) {
        done = true;
        break;
      }
    }
  }

  // Iterate through all uarch values we want to validate.
  bool failed = false;
  for (auto &model : models) {
    for (auto &[key, value] : expected_uarchevent_values) {
      auto actual_value = simif.read(model->get_addr_map().r_addr(key));
      if (actual_value != value) {
        fprintf(stderr,
                "FASED Test Harness -- %s did not match: Measured %d, Expected "
                "%d\n",
                key.c_str(),
                actual_value,
                value);
        failed = true;
      }
    }
  }

  return failed ? 1 : 0;
}

std::unique_ptr<simulation_t>
create_simulation(simif_t &simif,
                  widget_registry_t &registry,
                  const std::vector<std::string> &args) {
  return std::make_unique<fasedtests_top_t>(simif, registry, args);
}
