// See LICENSE for license details.

#include "simif.h"
#include <algorithm>
#include <inttypes.h>

midas_time_t timestamp() {
  struct timeval tv;
  gettimeofday(&tv, NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
}

double diff_secs(midas_time_t end, midas_time_t start) {
  return ((double)(end - start)) / TIME_DIV_CONST;
}

void simulation_t::record_start_times() {
  start_hcycle = sim.actual_hcycle();
  start_time = timestamp();
}

void simulation_t::record_end_times() {
  end_time = timestamp();
  end_tcycle = sim.actual_tcycle();
  end_hcycle = sim.actual_hcycle();
}

void simulation_t::print_simulation_performance_summary() {
  // Must call record_start_times and record_end_times before invoking this
  // function
  assert(start_hcycle != -1 && end_hcycle != 0 && "simulation not executed");

  const uint64_t hcycles = end_hcycle - start_hcycle;
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

int simulation_t::execute_simulation_flow() {
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

extern std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t *simif);

simif_t::simif_t(const TargetConfig &config,
                 const std::vector<std::string> &args)
    : config(config), loadmem(this,
                              LOADMEMWIDGET_0_substruct_create,
                              config.mem,
                              LOADMEMWIDGET_0_mem_data_chunk),
      clock(this, CLOCKBRIDGEMODULE_0_substruct_create),
      master(this, SIMULATIONMASTER_0_substruct_create),
      sim(create_simulation(args, this)) {
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
  }
}

void simif_t::target_init() {
  // Do any post-constructor initialization required before requesting MMIO
  while (!master.is_init_done())
    ;
  if (!fastloadmem && !load_mem_path.empty()) {
    loadmem.load_mem_from_file(load_mem_path);
  }
  host_mmio_init();
}

int simif_t::simulation_run() {
  target_init();

  if (do_zero_out_dram) {
    fprintf(stderr, "Zeroing out FPGA DRAM. This will take a few seconds...\n");
    loadmem.zero_out_dram();
  }

  return sim->execute_simulation_flow();
}
