// See LICENSE for license details
#include "firesim_top.h"

// FireSim library bridge drivers
// From firesim-lib/src/main/cc/bridges
#include "bridges/autocounter.h"
#include "bridges/blockdev.h"
#include "bridges/dromajo.h"
#include "bridges/groundtest.h"
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

firesim_top_t::firesim_top_t(int argc, char **argv) {
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

  add_bridge_driver(new heartbeat_t(this, args));

#ifdef RESETPULSEBRIDGEMODULE_0_PRESENT
  INSTANTIATE_RESET_PULSE(add_bridge_driver, 0)
#endif

  // DOC include start: UART Bridge Driver Registration
  // Here we instantiate our driver once for each bridge in the target
  // Golden Gate emits a <BridgeModuleClassName>_<id>_PRESENT macro for each
  // instance which you may use to conditionally instantiate your driver
#ifdef UARTBRIDGEMODULE_0_PRESENT
  // Create an instance of the constructor argument (this has all of
  // addresses of the BridgeModule's memory mapped registers)
  UARTBRIDGEMODULE_0_substruct_create;
  // Instantiate the driver; register it in the main simulation class
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_0_substruct, 0));
#endif

// Repeat the code above with modified indices as many times as necessary
// to support the maximum expected number of bridge instances
#ifdef UARTBRIDGEMODULE_1_PRESENT
  UARTBRIDGEMODULE_1_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_1_substruct, 1));
#endif
  // DOC include end: UART Bridge Driver Registration
#ifdef UARTBRIDGEMODULE_2_PRESENT
  UARTBRIDGEMODULE_2_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_2_substruct, 2));
#endif
#ifdef UARTBRIDGEMODULE_3_PRESENT
  UARTBRIDGEMODULE_3_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_3_substruct, 3));
#endif
#ifdef UARTBRIDGEMODULE_4_PRESENT
  UARTBRIDGEMODULE_4_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_4_substruct, 4));
#endif
#ifdef UARTBRIDGEMODULE_5_PRESENT
  UARTBRIDGEMODULE_5_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_5_substruct, 5));
#endif
#ifdef UARTBRIDGEMODULE_6_PRESENT
  UARTBRIDGEMODULE_6_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_6_substruct, 6));
#endif
#ifdef UARTBRIDGEMODULE_7_PRESENT
  UARTBRIDGEMODULE_7_substruct_create;
  add_bridge_driver(new uart_t(this, UARTBRIDGEMODULE_7_substruct, 7));
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
#ifdef FASEDMEMORYTIMINGMODEL_9
  INSTANTIATE_FASED(fpga_models.push_back, 9)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_10
  INSTANTIATE_FASED(fpga_models.push_back, 10)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_11
  INSTANTIATE_FASED(fpga_models.push_back, 11)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_12
  INSTANTIATE_FASED(fpga_models.push_back, 12)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_13
  INSTANTIATE_FASED(fpga_models.push_back, 13)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_14
  INSTANTIATE_FASED(fpga_models.push_back, 14)
#endif
#ifdef FASEDMEMORYTIMINGMODEL_15
  INSTANTIATE_FASED(fpga_models.push_back, 15)
#endif

#ifdef SERIALBRIDGEMODULE_0_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 0)
#endif
#ifdef SERIALBRIDGEMODULE_1_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 1)
#endif
#ifdef SERIALBRIDGEMODULE_2_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 2)
#endif
#ifdef SERIALBRIDGEMODULE_3_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 3)
#endif
#ifdef SERIALBRIDGEMODULE_4_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 4)
#endif
#ifdef SERIALBRIDGEMODULE_5_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 5)
#endif
#ifdef SERIALBRIDGEMODULE_6_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 6)
#endif
#ifdef SERIALBRIDGEMODULE_7_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 7)
#endif
#ifdef SERIALBRIDGEMODULE_8_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 8)
#endif
#ifdef SERIALBRIDGEMODULE_9_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 9)
#endif
#ifdef SERIALBRIDGEMODULE_10_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 10)
#endif
#ifdef SERIALBRIDGEMODULE_11_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 11)
#endif
#ifdef SERIALBRIDGEMODULE_12_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 12)
#endif
#ifdef SERIALBRIDGEMODULE_13_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 13)
#endif
#ifdef SERIALBRIDGEMODULE_14_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 14)
#endif
#ifdef SERIALBRIDGEMODULE_15_PRESENT
  INSTANTIATE_SERIAL(add_bridge_driver, 15)
#endif

#ifdef BLOCKDEVBRIDGEMODULE_struct_guard
#ifdef BLOCKDEVBRIDGEMODULE_0_PRESENT
  BLOCKDEVBRIDGEMODULE_0_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_0_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_0_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_0_substruct,
                                   0));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_1_PRESENT
  BLOCKDEVBRIDGEMODULE_1_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_1_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_1_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_1_substruct,
                                   1));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_2_PRESENT
  BLOCKDEVBRIDGEMODULE_2_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_2_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_2_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_2_substruct,
                                   2));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_3_PRESENT
  BLOCKDEVBRIDGEMODULE_3_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_3_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_3_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_3_substruct,
                                   3));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_4_PRESENT
  BLOCKDEVBRIDGEMODULE_4_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_4_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_4_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_4_substruct,
                                   4));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_5_PRESENT
  BLOCKDEVBRIDGEMODULE_5_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_5_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_5_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_5_substruct,
                                   5));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_6_PRESENT
  BLOCKDEVBRIDGEMODULE_6_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_6_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_6_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_6_substruct,
                                   6));
#endif
#ifdef BLOCKDEVBRIDGEMODULE_7_PRESENT
  BLOCKDEVBRIDGEMODULE_7_substruct_create;
  add_bridge_driver(new blockdev_t(this,
                                   args,
                                   BLOCKDEVBRIDGEMODULE_7_num_trackers,
                                   BLOCKDEVBRIDGEMODULE_7_latency_bits,
                                   BLOCKDEVBRIDGEMODULE_7_substruct,
                                   7));
#endif
#endif

#ifdef SIMPLENICBRIDGEMODULE_struct_guard
#ifdef SIMPLENICBRIDGEMODULE_0_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 0)
#endif
#ifdef SIMPLENICBRIDGEMODULE_1_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 1)
#endif
#ifdef SIMPLENICBRIDGEMODULE_2_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 2)
#endif
#ifdef SIMPLENICBRIDGEMODULE_3_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 3)
#endif
#ifdef SIMPLENICBRIDGEMODULE_4_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 4)
#endif
#ifdef SIMPLENICBRIDGEMODULE_5_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 5)
#endif
#ifdef SIMPLENICBRIDGEMODULE_6_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 6)
#endif
#ifdef SIMPLENICBRIDGEMODULE_7_PRESENT
  INSTANTIATE_SIMPLENIC(add_bridge_driver, 7)
#endif
#endif

#ifdef TRACERVBRIDGEMODULE_struct_guard
#ifdef TRACERVBRIDGEMODULE_0_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 0)
#endif
#ifdef TRACERVBRIDGEMODULE_1_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 1)
#endif
#ifdef TRACERVBRIDGEMODULE_2_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 2)
#endif
#ifdef TRACERVBRIDGEMODULE_3_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 3)
#endif
#ifdef TRACERVBRIDGEMODULE_4_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 4)
#endif
#ifdef TRACERVBRIDGEMODULE_5_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 5)
#endif
#ifdef TRACERVBRIDGEMODULE_6_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 6)
#endif
#ifdef TRACERVBRIDGEMODULE_7_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 7)
#endif
#ifdef TRACERVBRIDGEMODULE_8_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 8)
#endif
#ifdef TRACERVBRIDGEMODULE_9_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 9)
#endif
#ifdef TRACERVBRIDGEMODULE_10_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 10)
#endif
#ifdef TRACERVBRIDGEMODULE_11_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 11)
#endif
#ifdef TRACERVBRIDGEMODULE_12_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 12)
#endif
#ifdef TRACERVBRIDGEMODULE_13_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 13)
#endif
#ifdef TRACERVBRIDGEMODULE_14_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 14)
#endif
#ifdef TRACERVBRIDGEMODULE_15_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 15)
#endif
#ifdef TRACERVBRIDGEMODULE_16_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 16)
#endif
#ifdef TRACERVBRIDGEMODULE_17_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 17)
#endif
#ifdef TRACERVBRIDGEMODULE_18_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 18)
#endif
#ifdef TRACERVBRIDGEMODULE_19_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 19)
#endif
#ifdef TRACERVBRIDGEMODULE_20_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 20)
#endif
#ifdef TRACERVBRIDGEMODULE_21_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 21)
#endif
#ifdef TRACERVBRIDGEMODULE_22_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 22)
#endif
#ifdef TRACERVBRIDGEMODULE_23_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 23)
#endif
#ifdef TRACERVBRIDGEMODULE_24_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 24)
#endif
#ifdef TRACERVBRIDGEMODULE_25_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 25)
#endif
#ifdef TRACERVBRIDGEMODULE_26_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 26)
#endif
#ifdef TRACERVBRIDGEMODULE_27_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 27)
#endif
#ifdef TRACERVBRIDGEMODULE_28_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 28)
#endif
#ifdef TRACERVBRIDGEMODULE_29_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 29)
#endif
#ifdef TRACERVBRIDGEMODULE_30_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 30)
#endif
#ifdef TRACERVBRIDGEMODULE_31_PRESENT
  INSTANTIATE_TRACERV(add_bridge_driver, 31)
#endif
#endif

#ifdef DROMAJOBRIDGEMODULE_struct_guard
#ifdef DROMAJOBRIDGEMODULE_0_PRESENT
  DROMAJOBRIDGEMODULE_0_substruct_create;
    add_bridge_driver(new dromajo_t(
            this, args,
            DROMAJOBRIDGEMODULE_0_iaddr_width,
            DROMAJOBRIDGEMODULE_0_insn_width,
            DROMAJOBRIDGEMODULE_0_wdata_width,
            DROMAJOBRIDGEMODULE_0_cause_width,
            DROMAJOBRIDGEMODULE_0_tval_width,
            DROMAJOBRIDGEMODULE_0_num_traces,
            DROMAJOBRIDGEMODULE_0_substruct,
            DROMAJOBRIDGEMODULE_0_to_cpu_stream_dma_address,
            DROMAJOBRIDGEMODULE_0_to_cpu_stream_count_address,
            DROMAJOBRIDGEMODULE_0_to_cpu_stream_full_address)
#endif
#endif

#ifdef GROUNDTESTBRIDGEMODULE_struct_guard
#ifdef GROUNDTESTBRIDGEMODULE_0_PRESENT
    GROUNDTESTBRIDGEMODULE_0_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_0_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_1_PRESENT
    GROUNDTESTBRIDGEMODULE_1_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_1_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_2_PRESENT
    GROUNDTESTBRIDGEMODULE_2_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_2_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_3_PRESENT
    GROUNDTESTBRIDGEMODULE_3_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_3_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_4_PRESENT
    GROUNDTESTBRIDGEMODULE_4_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_4_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_5_PRESENT
    GROUNDTESTBRIDGEMODULE_5_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_5_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_6_PRESENT
    GROUNDTESTBRIDGEMODULE_6_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_6_substruct));
#endif
#ifdef GROUNDTESTBRIDGEMODULE_7_PRESENT
    GROUNDTESTBRIDGEMODULE_7_substruct_create;
    add_bridge_driver(new groundtest_t(
            this, args, GROUNDTESTBRIDGEMODULE_7_substruct));
#endif
#endif

#ifdef AUTOCOUNTERBRIDGEMODULE_0_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 0)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_1_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 1)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_2_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 2)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_3_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 3)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_4_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 4)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_5_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 5)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_6_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 6)
#endif
#ifdef AUTOCOUNTERBRIDGEMODULE_7_PRESENT
    INSTANTIATE_AUTOCOUNTER(add_bridge_driver, 7)
#endif

#ifdef ASSERTBRIDGEMODULE_0_PRESENT
    ASSERTBRIDGEMODULE_0_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_0_substruct,
                                                   ASSERTBRIDGEMODULE_0_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_1_PRESENT
    ASSERTBRIDGEMODULE_1_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_1_substruct,
                                                   ASSERTBRIDGEMODULE_1_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_2_PRESENT
    ASSERTBRIDGEMODULE_2_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_2_substruct,
                                                   ASSERTBRIDGEMODULE_2_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_3_PRESENT
    ASSERTBRIDGEMODULE_3_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_3_substruct,
                                                   ASSERTBRIDGEMODULE_3_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_4_PRESENT
    ASSERTBRIDGEMODULE_4_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_4_substruct,
                                                   ASSERTBRIDGEMODULE_4_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_5_PRESENT
    ASSERTBRIDGEMODULE_5_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_5_substruct,
                                                   ASSERTBRIDGEMODULE_5_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_6_PRESENT
    ASSERTBRIDGEMODULE_6_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_6_substruct,
                                                   ASSERTBRIDGEMODULE_6_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_7_PRESENT
    ASSERTBRIDGEMODULE_7_substruct_create
    add_bridge_driver(new synthesized_assertions_t(this, args,
                                                   ASSERTBRIDGEMODULE_7_substruct,
                                                   ASSERTBRIDGEMODULE_7_assert_messages));
#endif

#ifdef PRINTBRIDGEMODULE_0_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,0)
#endif
#ifdef PRINTBRIDGEMODULE_1_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,1)
#endif
#ifdef PRINTBRIDGEMODULE_2_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,2)
#endif
#ifdef PRINTBRIDGEMODULE_3_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,3)
#endif
#ifdef PRINTBRIDGEMODULE_4_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,4)
#endif
#ifdef PRINTBRIDGEMODULE_5_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,5)
#endif
#ifdef PRINTBRIDGEMODULE_6_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,6)
#endif
#ifdef PRINTBRIDGEMODULE_7_PRESENT
    INSTANTIATE_PRINTF(add_bridge_driver,7)
#endif
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
  for (auto mod : fpga_models) {
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
    zero_out_dram();
  }

  fprintf(stderr, "Commencing simulation.\n");
  record_start_times();

  while (!simulation_complete() && !finished_scheduled_tasks()) {
    run_scheduled_tasks();
    take_steps(get_largest_stepsize(), false);
    while (!done() && !simulation_complete()) {
      for (auto &e : bridges)
        e->tick();
    }
  }

  record_end_times();
  fprintf(stderr, "\nSimulation complete.\n");
}

int firesim_top_t::teardown() {
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

  for (auto &e : fpga_models) {
    e->finish();
  }

  for (auto &e : bridges) {
    e->finish();
  }

  this->host_finish();
  return ((exitcode != 0) || max_cycles_timeout) ? EXIT_FAILURE : EXIT_SUCCESS;
}
