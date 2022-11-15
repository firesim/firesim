// See LICENSE for license details.
#ifndef RTLSIM
#include "simif_f1.h"
#else
#include "simif_emul.h"
#endif

#include "fasedtests_top.h"
#include "test_harness_bridge.h"
// MIDAS-defined bridges
#include "bridges/fased_memory_timing_model.h"
#include "bridges/reset_pulse.h"
#include "bridges/synthesized_assertions.h"
#include "bridges/synthesized_prints.h"

fasedtests_top_t::fasedtests_top_t(int argc, char **argv) {
  std::vector<std::string> args(argv + 1, argv + argc);
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

#ifdef RESETPULSEBRIDGEMODULE_0_PRESENT
  INSTANTIATE_RESET_PULSE(add_bridge_driver, 0)
#endif

#ifdef FASEDMEMORYTIMINGMODEL_0
  INSTANTIATE_FASED(fpga_models.push_back, 0)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_1
  INSTANTIATE_FASED(fpga_models.push_back, 1)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_2
  INSTANTIATE_FASED(fpga_models.push_back, 2)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_3
  INSTANTIATE_FASED(fpga_models.push_back, 3)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_4
  INSTANTIATE_FASED(fpga_models.push_back, 4)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_5
  INSTANTIATE_FASED(fpga_models.push_back, 5)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_6
  INSTANTIATE_FASED(fpga_models.push_back, 6)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_7
  INSTANTIATE_FASED(fpga_models.push_back, 7)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_8
  INSTANTIATE_FASED(fpga_models.push_back, 8)
#endif

  // Add functions you'd like to periodically invoke on a paused simulator here.
  if (profile_interval != -1) {
    register_task([this]() { return this->profile_models(); }, 0);
  }
  // Test harness.
  AddressMap fased_addr_map =
      AddressMap(FASEDMEMORYTIMINGMODEL_0_R_num_registers,
                 (const unsigned int *)FASEDMEMORYTIMINGMODEL_0_R_addrs,
                 (const char *const *)FASEDMEMORYTIMINGMODEL_0_R_names,
                 FASEDMEMORYTIMINGMODEL_0_W_num_registers,
                 (const unsigned int *)FASEDMEMORYTIMINGMODEL_0_W_addrs,
                 (const char *const *)FASEDMEMORYTIMINGMODEL_0_W_names);
  add_bridge_driver(new test_harness_bridge_t(this, fased_addr_map, args));
}

bool fasedtests_top_t::simulation_complete() {
  bool is_complete = false;
  for (auto &e : bridges) {
    is_complete |= e->terminate();
  }
  return is_complete;
}

uint64_t fasedtests_top_t::profile_models() {
  for (auto mod : fpga_models) {
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

int fasedtests_top_t::run() {
  for (auto &e : fpga_models) {
    e->init();
  }

  for (auto &e : bridges) {
    e->init();
  }

  if (do_zero_out_dram) {
    fprintf(stderr, "Zeroing out FPGA DRAM. This will take a few seconds...\n");
    zero_out_dram();
  }
  fprintf(stderr, "Commencing simulation.\n");
  record_start_times();

  while (!simulation_complete() && !finished_scheduled_tasks()) {
    run_scheduled_tasks();
    step(get_largest_stepsize(), false);
    while (!done() && !simulation_complete()) {
      for (auto &e : bridges)
        e->tick();
    }
  }

  record_end_times();
  fprintf(stderr, "\nSimulation complete.\n");

  (void)actual_tcycle();

  int exitcode = exit_code();

  // If the simulator is idle and we've gotten here without any bridge
  // indicating doneness, we've advanced to the +max_cycles limit in the fastest
  // target clock domain.
  bool max_cycles_timeout =
      !simulation_complete() && done() && finished_scheduled_tasks();

  if (exitcode != 0) {
    fprintf(stderr,
            "*** FAILED *** (code = %d) after %" PRIu64 " cycles\n",
            exitcode,
            get_end_tcycle());
  } else if (max_cycles_timeout) {
    fprintf(stderr,
            "*** FAILED *** +max_cycles specified timeout after %" PRIu64
            " cycles\n",
            get_end_tcycle());
  } else {
    fprintf(
        stderr, "*** PASSED *** after %" PRIu64 " cycles\n", get_end_tcycle());
  }

  print_simulation_performance_summary();

  for (auto e : fpga_models) {
    e->finish();
  }

  for (auto &e : bridges) {
    e->finish();
  }
  this->host_finish();
  return ((exitcode != 0) || max_cycles_timeout) ? EXIT_FAILURE : EXIT_SUCCESS;
}

// top for RTL sim
class fasedtests_driver_t :
#ifdef RTLSIM
    public simif_emul_t,
    public fasedtests_top_t
#else
    public simif_f1_t,
    public fasedtests_top_t
#endif
{
public:
#ifdef RTLSIM
  fasedtests_driver_t(int argc, char **argv) : fasedtests_top_t(argc, argv){};
#else
  fasedtests_driver_t(int argc, char **argv)
      : simif_f1_t(argc, argv), fasedtests_top_t(argc, argv){};
#endif
};

int main(int argc, char **argv) {
  fasedtests_driver_t driver(argc, argv);
  driver.init(argc, argv);
  return driver.run();
}
