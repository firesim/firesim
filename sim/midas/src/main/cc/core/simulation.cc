// See LICENSE for license details.

#include "simulation.h"
#include "bridges/clock.h"
#include "bridges/loadmem.h"
#include "bridges/master.h"
#include "core/bridge_driver.h"
#include "core/simif.h"
#include "core/stream_engine.h"
#include "core/timing.h"

#include <cassert>
#include <cinttypes>
#include <cstdio>

simulation_t::simulation_t(widget_registry_t &registry,
                           const std::vector<std::string> &args)
    : registry(registry), clock(registry.get_widget<clockmodule_t>()) {
  bool fastloadmem = false;
  for (auto &arg : args) {
    if (arg.find("+fastloadmem") == 0) {
      fastloadmem = true;
    }
    if (arg.find("+loadmem=") == 0) {
      load_mem_path = arg.c_str() + 9;
    }
    if (arg.find("+zero-out-dram") == 0) {
      do_zero_out_dram = true;
    }
    if (arg.find("+check-fingerprint") == 0) {
      check_fingerprint_only = true;
    }
    if (arg.find("+write-fingerprint=") == 0) {
      write_fingerprint_only = atoi(arg.c_str() + 19);
    }
  }

  if (fastloadmem)
    load_mem_path.clear();
}

void simulation_t::record_start_times() {
  start_hcycle = clock.hcycle();
  start_time = timestamp();
}

void simulation_t::record_end_times() {
  end_time = timestamp();
  end_tcycle = clock.tcycle();
  end_hcycle = clock.hcycle();
}

void simulation_t::print_simulation_performance_summary() {
  // Must call record_start_times and record_end_times before invoking this
  // function
  assert(start_hcycle.has_value() && end_hcycle.has_value() &&
         "simulation not executed");

  const uint64_t hcycles = *end_hcycle - *start_hcycle;
  const double sim_time = diff_secs(end_time, start_time);
  const double sim_speed = ((double)end_tcycle) / (sim_time * 1000.0);
  const double measured_host_frequency =
      ((double)hcycles) / (sim_time * 1000.0);
  const double fmr = ((double)hcycles / end_tcycle);

  fprintf(stderr, "\nEmulation Performance Summary\n");
  fprintf(stderr, "------------------------------\n");
  fprintf(stderr, "Wallclock Time Elapsed: %.1f s\n", sim_time);
  // Provide enough sig-figs to let the report be useful in RTL sim
  fprintf(stderr, "Host Frequency: ");
  if (measured_host_frequency > 1000.0) {
    fprintf(stderr, "%.3f MHz\n", measured_host_frequency / 1000.0);
  } else {
    fprintf(stderr, "%.3f KHz\n", measured_host_frequency);
  }

  fprintf(stderr, "Target Cycles Emulated: %" PRIu64 "\n", end_tcycle);
  fprintf(stderr, "Effective Target Frequency: ");
  if (sim_speed > 1000.0) {
    fprintf(stderr, "%.3f MHz\n", sim_speed / 1000.0);
  } else {
    fprintf(stderr, "%.3f KHz\n", sim_speed);
  }
  fprintf(stderr, "FMR: %.2f\n", fmr);
  fprintf(stderr,
          "Note: The latter three figures are based on the fastest "
          "target clock.\n");
}

void simulation_t::simulation_init() {
  for (auto *bridge : registry.get_all_bridges()) {
    bridge->init();
  }
}

void simulation_t::simulation_finish() {
  for (auto *bridge : registry.get_all_bridges()) {
    bridge->finish();
  }
}

int simulation_t::execute_simulation_flow() {
  wait_for_init();

  // following fingerprint logic uses 'exit' instead of 'return' to avoid
  // issues w/ deconstructors not having initialized values
  auto &master = registry.get_widget<master_t>();
  if (check_fingerprint_only || write_fingerprint_only.has_value()) {
    if (check_fingerprint_only && write_fingerprint_only.has_value()) {
      fprintf(stderr, "Unable to both check/write FireSim fingerprint\n");
      exit(EXIT_FAILURE);
    }

    if (check_fingerprint_only && master.check_fingerprint()) {
      fprintf(stderr, "Invalid FireSim fingerprint\n");
      exit(EXIT_FAILURE);
    }
    if (write_fingerprint_only.has_value()) {
      master.write_fingerprint(write_fingerprint_only.value());
    }
    exit(EXIT_SUCCESS);
  } else {
    if (master.check_fingerprint()) {
      fprintf(stderr, "Invalid FireSim fingerprint\n");
      exit(EXIT_FAILURE);
    }
  }

  if (auto *stream = registry.get_stream_engine()) {
    stream->init();
  }

  init_dram();

  simulation_init();

  record_start_times();
  fprintf(stderr, "Commencing simulation.\n");
  const int exit_code = simulation_run();
  fprintf(stderr, "\nSimulation complete.\n");
  record_end_times();

  simulation_finish();

  const bool timeout = simulation_timed_out();

  if (exit_code != 0) {
    fprintf(stderr,
            "*** FAILED *** (code = %d) after %" PRIu64 " cycles\n",
            exit_code,
            end_tcycle);
  } else if (timeout) {
    fprintf(stderr,
            "*** FAILED *** simulation timed out after %" PRIu64 " cycles\n",
            end_tcycle);
  } else {
    fprintf(stderr, "*** PASSED *** after %" PRIu64 " cycles\n", end_tcycle);
  }

  print_simulation_performance_summary();

  return timeout ? EXIT_FAILURE : exit_code;
}

void simulation_t::wait_for_init() {
  auto &master = registry.get_widget<master_t>();
  while (!master.is_init_done())
    ;
}

void simulation_t::init_dram() {
  if (auto *loadmem = registry.get_widget_opt<loadmem_t>()) {
    if (do_zero_out_dram) {
      fprintf(stderr,
              "Zeroing out FPGA DRAM. This will take a few seconds...\n");
      loadmem->zero_out_dram();
    }

    if (!load_mem_path.empty()) {
      loadmem->load_mem_from_file(load_mem_path);
    }
  } else {
    if (do_zero_out_dram || !load_mem_path.empty()) {
      fprintf(stderr,
              "Skipping memory initialization: target does not use DRAM\n");
    }
  }
}
