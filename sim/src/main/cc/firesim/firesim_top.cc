// See LICENSE for license details
#include "firesim_top.h"

// FireSim library bridge drivers
// From firesim-lib/src/main/cc/bridges
#include "bridges/autocounter.h"
#include "bridges/blockdev.h"
#include "bridges/dromajo.h"
#include "bridges/groundtest.h"
#include "bridges/plusargs.h"
#include "bridges/reset_pulse.h"
#include "bridges/serial.h"
#include "bridges/simplenic.h"
#include "bridges/tracerv.h"
#include "bridges/uart.h"

// Golden Gate provided bridge drivers
#include "bridges/fased_memory_timing_model.h"
#include "bridges/fpga_model.h"
#include "bridges/heartbeat.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"

firesim_top_t::firesim_top_t(const std::vector<std::string> &args,
                             simif_t *simif)
    : simif(simif) {
  max_cycles = -1;
  profile_interval = max_cycles;

  for (auto &arg : args) {
    if (arg.find("+max-cycles=") == 0) {
      max_cycles = atoi(arg.c_str() + 12);
    }
    if (arg.find("+profile-interval=") == 0) {
      profile_interval = atoi(arg.c_str() + 18);
    }
    if (arg.find("+zero-out-dram") == 0) {
      do_zero_out_dram = true;
    }
  }

  add_bridge_driver(new heartbeat_t(simif, args));

  // DOC include start: Bridge Driver Registration
  // Here we instantiate our driver once for each bridge in the target
  // Golden Gate emits a <BridgeModuleClassName>_<id>_PRESENT macro for each
  // instance which you may use to conditionally instantiate your driver.
  // This file can be included in the setup method of any top-level to pass
  // an instance of each driver to the `add_bridge_driver` method. Drivers can
  // be distinguished by overloading the method with the appropriate type.
#include "constructor.h"
  // DOC include end: Bridge Driver Registration

  // Add functions you'd like to periodically invoke on a paused simulator here.
  if (profile_interval != -1) {
    register_task([this]() { return this->profile_models(); }, 0);
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

void firesim_top_t::run() {
  for (auto &e : fpga_models) {
    e->init();
  }

  for (auto &e : bridges) {
    e->init();
  }

  if (do_zero_out_dram) {
    fprintf(stderr, "Zeroing out FPGA DRAM. This will take a few minutes...\n");
    simif->zero_out_dram();
  }

  fprintf(stderr, "Commencing simulation.\n");
  simif->record_start_times();

  while (!simulation_complete() && !finished_scheduled_tasks()) {
    run_scheduled_tasks();
    simif->take_steps(get_largest_stepsize(), false);
    while (!simif->done() && !simulation_complete()) {
      for (auto &e : bridges)
        e->tick();
    }
  }

  simif->record_end_times();
  fprintf(stderr, "\nSimulation complete.\n");
}

int firesim_top_t::teardown() {
  int exitcode = exit_code();

  // If the simulator is idle and we've gotten here without any bridge
  // indicating doneness, we've advanced to the +max_cycles limit in the
  // fastest target clock domain.
  bool max_cycles_timeout =
      !simulation_complete() && simif->done() && finished_scheduled_tasks();

  if (exitcode != 0) {
    fprintf(stderr,
            "*** FAILED *** (code = %d) after %" PRIu64 " cycles\n",
            exitcode,
            simif->get_end_tcycle());
  } else if (max_cycles_timeout) {
    fprintf(stderr,
            "*** FAILED *** +max_cycles specified timeout after %" PRIu64
            " cycles\n",
            simif->get_end_tcycle());
  } else {
    fprintf(stderr,
            "*** PASSED *** after %" PRIu64 " cycles\n",
            simif->get_end_tcycle());
  }

  simif->print_simulation_performance_summary();

  for (auto &e : fpga_models) {
    e->finish();
  }

  for (auto &e : bridges) {
    e->finish();
  }

  simif->host_finish();
  return ((exitcode != 0) || max_cycles_timeout) ? EXIT_FAILURE : EXIT_SUCCESS;
}
