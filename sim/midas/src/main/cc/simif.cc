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

extern std::unique_ptr<simulation_t>
create_simulation(const std::vector<std::string> &args, simif_t *simif);

simif_t::simif_t(const std::vector<std::string> &args)
    : loadmem(this, LOADMEMWIDGET_0_substruct_create, MEM_DATA_CHUNK),
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
    if (arg.find("+seed=") == 0) {
      seed = strtoll(arg.c_str() + 6, NULL, 10);
      fprintf(stderr, "Using custom SEED: %ld\n", seed);
    }
    if (arg.find("+zero-out-dram") == 0) {
      do_zero_out_dram = true;
    }
  }

  gen.seed(seed);
  fprintf(stderr,
          "random min: 0x%" PRIx64 ", random max: 0x%" PRIx64 "\n",
          gen.min(),
          gen.max());
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

  sim->simulation_init();

  record_start_times();
  int exitcode = sim->simulation_run();
  record_end_times();

  sim->simulation_finish();

  print_simulation_performance_summary();
  return exitcode;
}

void simif_t::record_start_times() {
  this->start_hcycle = clock.hcycle();
  this->start_time = timestamp();
}

void simif_t::record_end_times() {
  this->end_time = timestamp();
  this->end_tcycle = actual_tcycle();
  this->end_hcycle = clock.hcycle();
}

void simif_t::print_simulation_performance_summary() {
  // Must call record_start_times and record_end_times before invoking this
  // function
  assert(start_hcycle != -1);
  assert(end_hcycle != 0);
  uint64_t hcycles = end_hcycle - start_hcycle;
  double sim_time = diff_secs(end_time, start_time);
  double sim_speed = ((double)end_tcycle) / (sim_time * 1000.0);
  double measured_host_frequency = ((double)hcycles) / (sim_time * 1000.0);
  double fmr = ((double)hcycles / end_tcycle);

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
