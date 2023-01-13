// See LICENSE for license details
#ifndef __TRACERV_H
#define __TRACERV_H

#include "bridges/bridge_driver.h"
#include "bridges/clock_info.h"
#include "bridges/tracerv/trace_tracker.h"
#include "bridges/tracerv/tracerv_processing.h"
#include <functional>
#include <vector>

typedef struct TRACERVBRIDGEMODULE_struct {
  uint64_t initDone;
  uint64_t traceEnable;
  uint64_t hostTriggerPCStartHigh;
  uint64_t hostTriggerPCStartLow;
  uint64_t hostTriggerPCEndHigh;
  uint64_t hostTriggerPCEndLow;
  uint64_t hostTriggerCycleCountStartHigh;
  uint64_t hostTriggerCycleCountStartLow;
  uint64_t hostTriggerCycleCountEndHigh;
  uint64_t hostTriggerCycleCountEndLow;
  uint64_t hostTriggerStartInst;
  uint64_t hostTriggerStartInstMask;
  uint64_t hostTriggerEndInst;
  uint64_t hostTriggerEndInstMask;
  uint64_t triggerSelector;
} TRACERVBRIDGEMODULE_struct;

#ifdef TRACERVBRIDGEMODULE_checks
TRACERVBRIDGEMODULE_checks;
#endif // TRACERVBRIDGEMODULE_checks

// Bridge Driver Instantiation Template
#define INSTANTIATE_TRACERV(FUNC, IDX)                                         \
  FUNC(new tracerv_t(simif,                                                    \
                     args,                                                     \
                     TRACERVBRIDGEMODULE_##IDX##_substruct_create,             \
                     TRACERVBRIDGEMODULE_##IDX##_to_cpu_stream_idx,            \
                     TRACERVBRIDGEMODULE_##IDX##_to_cpu_stream_depth,          \
                     TRACERVBRIDGEMODULE_##IDX##_max_core_ipc,                 \
                     TRACERVBRIDGEMODULE_##IDX##_clock_domain_name,            \
                     TRACERVBRIDGEMODULE_##IDX##_clock_multiplier,             \
                     TRACERVBRIDGEMODULE_##IDX##_clock_divisor,                \
                     IDX));

class tracerv_t : public bridge_driver_t

{
public:
  tracerv_t(simif_t *sim,
            const std::vector<std::string> &args,
            const TRACERVBRIDGEMODULE_struct &mmio_addrs,
            const int stream_idx,
            const int stream_depth,
            const unsigned int max_core_ipc,
            const char *const clock_domain_name,
            const unsigned int clock_multiplier,
            const unsigned int clock_divisor,
            int tracerno);
  ~tracerv_t();

  virtual void init();
  virtual void tick();
  virtual bool terminate() { return false; }
  virtual int exit_code() { return 0; }
  virtual void finish() { flush(); };
  void set_on_instruction_received(std::function<void(uint64_t, uint64_t)> cb);

private:
  const TRACERVBRIDGEMODULE_struct mmio_addrs;
  const int stream_idx;
  const int stream_depth;

public:
  const int max_core_ipc;

private:
  ClockInfo clock_info;

  FILE *tracefile;
  uint64_t cur_cycle;
  uint64_t trace_trigger_start, trace_trigger_end;
  uint32_t trigger_start_insn = 0;
  uint32_t trigger_start_insn_mask = 0;
  uint32_t trigger_stop_insn = 0;
  uint32_t trigger_stop_insn_mask = 0;
  uint32_t trigger_selector;
  uint64_t trigger_start_pc = 0;
  uint64_t trigger_stop_pc = 0;

  // TODO: rename this from linuxbin
  ObjdumpedBinary *linuxbin;
  TraceTracker *trace_tracker;

  bool human_readable = false;
  // If no filename is provided, the instruction trace is not collected
  // and the bridge drops all tokens to improve FMR
  bool trace_enabled = true;
  // Used in unit testing to check TracerV is correctly pulling instuctions off
  // the target
  bool test_output = false;
  long dma_addr;
  std::string tracefilename;
  std::string dwarf_file_name;
  bool fireperf = false;
  bool init_ran = false;
  std::function<void(uint64_t, uint64_t)> on_instruction_received = NULL;

  size_t process_tokens(int num_beats, int minium_batch_beats);
  int beats_available_stable();
public:
  void flush();
};

#endif // __TRACERV_H
