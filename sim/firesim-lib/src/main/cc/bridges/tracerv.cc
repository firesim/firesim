// See LICENSE for license details
#ifdef TRACERVBRIDGEMODULE_struct_guard

#include "tracerv.h"

#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <sys/mman.h>

// put FIREPERF in a mode that writes a simple log for processing later.
// useful for iterating on software side only without re-running on FPGA.
//#define FIREPERF_LOGGER

constexpr uint64_t valid_mask = (1ULL << 40);

tracerv_t::tracerv_t(simif_t *sim,
                     std::vector<std::string> &args,
                     TRACERVBRIDGEMODULE_struct *mmio_addrs,
                     int stream_idx,
                     int stream_depth,
                     const unsigned int max_core_ipc,
                     const char *const clock_domain_name,
                     const unsigned int clock_multiplier,
                     const unsigned int clock_divisor,
                     int tracerno)
    : bridge_driver_t(sim), mmio_addrs(mmio_addrs), stream_idx(stream_idx),
      stream_depth(stream_depth), max_core_ipc(max_core_ipc),
      clock_info(clock_domain_name, clock_multiplier, clock_divisor) {
  // Biancolin: move into elaboration
  assert(this->max_core_ipc <= 7 &&
         "TracerV only supports cores with a maximum IPC <= 7");
  const char *tracefilename = NULL;
  const char *dwarf_file_name = NULL;
  this->tracefile = NULL;

  this->trace_trigger_start = 0;
  this->trace_trigger_end = ULONG_MAX;
  this->trigger_selector = 0;
  this->tracefilename = "";
  this->dwarf_file_name = "";

  long outputfmtselect = 0;

  std::string suffix = std::string("=");
  std::string tracefile_arg = std::string("+tracefile") + suffix;
  std::string tracestart_arg = std::string("+trace-start") + suffix;
  std::string traceend_arg = std::string("+trace-end") + suffix;
  std::string traceselect_arg = std::string("+trace-select") + suffix;
  // Testing: provides a reference file to diff the collected trace against
  std::string testoutput_arg = std::string("+trace-test-output");
  // Formats the output before dumping the trace to file
  std::string humanreadable_arg = std::string("+trace-humanreadable");

  std::string trace_output_format_arg =
      std::string("+trace-output-format") + suffix;
  std::string dwarf_file_arg = std::string("+dwarf-file-name") + suffix;

  for (auto &arg : args) {
    if (arg.find(tracefile_arg) == 0) {
      tracefilename = const_cast<char *>(arg.c_str()) + tracefile_arg.length();
      this->tracefilename = std::string(tracefilename);
    }
    if (arg.find(traceselect_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + traceselect_arg.length();
      this->trigger_selector = atol(str);
    }
    // These next two arguments are overloaded to provide trigger start and
    // stop condition information based on setting of the +trace-select
    if (arg.find(tracestart_arg) == 0) {
      // Start and end cycles are given in decimal
      char *str = const_cast<char *>(arg.c_str()) + tracestart_arg.length();
      this->trace_trigger_start = this->clock_info.to_local_cycles(atol(str));
      // PCs values, and instruction and mask encodings are given in hex
      uint64_t mask_and_insn = strtoul(str, NULL, 16);
      this->trigger_start_insn = (uint32_t)mask_and_insn;
      this->trigger_start_insn_mask = mask_and_insn >> 32;
      this->trigger_start_pc = mask_and_insn;
    }
    if (arg.find(traceend_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + traceend_arg.length();
      this->trace_trigger_end = this->clock_info.to_local_cycles(atol(str));

      uint64_t mask_and_insn = strtoul(str, NULL, 16);
      this->trigger_stop_insn = (uint32_t)mask_and_insn;
      this->trigger_stop_insn_mask = mask_and_insn >> 32;
      this->trigger_stop_pc = mask_and_insn;
    }
    if (arg.find(testoutput_arg) == 0) {
      this->test_output = true;
    }
    if (arg.find(trace_output_format_arg) == 0) {
      char *str =
          const_cast<char *>(arg.c_str()) + trace_output_format_arg.length();
      outputfmtselect = atol(str);
    }
    if (arg.find(dwarf_file_arg) == 0) {
      dwarf_file_name =
          const_cast<char *>(arg.c_str()) + dwarf_file_arg.length();
      this->dwarf_file_name = std::string(dwarf_file_name);
    }
  }

  if (tracefilename) {
    // giving no tracefilename means we will create NO tracefiles
    std::string tfname = std::string(tracefilename) + std::string("-C") +
                         std::to_string(tracerno);
    this->tracefile = fopen(tfname.c_str(), "w");
    if (!this->tracefile) {
      fprintf(stderr, "Could not open Trace log file: %s\n", tracefilename);
      abort();
    }
    fputs(this->clock_info.file_header().c_str(), this->tracefile);

    // This must be kept consistent with config_runtime.ini's output_format.
    // That file's comments are the single source of truth for this.
    if (outputfmtselect == 0) {
      this->human_readable = true;
      this->fireperf = false;
    } else if (outputfmtselect == 1) {
      this->human_readable = false;
      this->fireperf = false;
    } else if (outputfmtselect == 2) {
      this->human_readable = false;
      this->fireperf = true;
    } else {
      fprintf(stderr, "Invalid trace format arg\n");
    }
  } else {
    fprintf(
        stderr,
        "TraceRV %d: Tracing disabled, since +tracefile was not provided.\n",
        tracerno);
    this->trace_enabled = false;
  }

  if (fireperf) {
    if (this->dwarf_file_name.compare("") == 0) {
      fprintf(stderr, "+fireperf specified but no +dwarf-file-name given\n");
      abort();
    }
    this->trace_tracker =
        new TraceTracker(this->dwarf_file_name, this->tracefile);
  }
}

tracerv_t::~tracerv_t() {
  if (this->tracefile) {
    fclose(this->tracefile);
  }
  free(this->mmio_addrs);
}

void tracerv_t::init() {
  if (!this->trace_enabled) {
    // Explicitly disable token collection in the bridge if no tracefile was
    // provided to improve FMR
    write(this->mmio_addrs->traceEnable, 0);
  }

  // Configure the trigger even if tracing is disabled, as other
  // instrumentation, like autocounter, may use tracerv-hosted trigger sources.
  if (this->trigger_selector == 1) {
    write(this->mmio_addrs->triggerSelector, this->trigger_selector);
    write(this->mmio_addrs->hostTriggerCycleCountStartHigh,
          this->trace_trigger_start >> 32);
    write(this->mmio_addrs->hostTriggerCycleCountStartLow,
          this->trace_trigger_start & ((1ULL << 32) - 1));
    write(this->mmio_addrs->hostTriggerCycleCountEndHigh,
          this->trace_trigger_end >> 32);
    write(this->mmio_addrs->hostTriggerCycleCountEndLow,
          this->trace_trigger_end & ((1ULL << 32) - 1));
    printf("TracerV: Trigger enabled from %lu to %lu cycles\n",
           trace_trigger_start,
           trace_trigger_end);
  } else if (this->trigger_selector == 2) {
    write(this->mmio_addrs->triggerSelector, this->trigger_selector);
    write(this->mmio_addrs->hostTriggerPCStartHigh,
          this->trigger_start_pc >> 32);
    write(this->mmio_addrs->hostTriggerPCStartLow,
          this->trigger_start_pc & ((1ULL << 32) - 1));
    write(this->mmio_addrs->hostTriggerPCEndHigh, this->trigger_stop_pc >> 32);
    write(this->mmio_addrs->hostTriggerPCEndLow,
          this->trigger_stop_pc & ((1ULL << 32) - 1));
    printf("TracerV: Trigger enabled from instruction address %lx to %lx\n",
           trigger_start_pc,
           trigger_stop_pc);
  } else if (this->trigger_selector == 3) {
    write(this->mmio_addrs->triggerSelector, this->trigger_selector);
    write(this->mmio_addrs->hostTriggerStartInst, this->trigger_start_insn);
    write(this->mmio_addrs->hostTriggerStartInstMask,
          this->trigger_start_insn_mask);
    write(this->mmio_addrs->hostTriggerEndInst, this->trigger_stop_insn);
    write(this->mmio_addrs->hostTriggerEndInstMask,
          this->trigger_stop_insn_mask);
    printf("TracerV: Trigger enabled from start trigger instruction %x masked "
           "with %x, to end trigger instruction %x masked with %x\n",
           this->trigger_start_insn,
           this->trigger_start_insn_mask,
           this->trigger_stop_insn,
           this->trigger_stop_insn_mask);
  } else {
    // Writing 0 to triggerSelector permanently enables the trigger
    write(this->mmio_addrs->triggerSelector, this->trigger_selector);
    printf("TracerV: No trigger selected. Trigger enabled from %lu to %lu "
           "cycles\n",
           0ul,
           ULONG_MAX);
  }
  write(this->mmio_addrs->initDone, true);
}

size_t tracerv_t::process_tokens(int num_beats, int minimum_batch_beats) {
  size_t maximum_batch_bytes = num_beats * BridgeConstants::STREAM_WIDTH_BYTES;
  size_t minimum_batch_bytes =
      minimum_batch_beats * BridgeConstants::STREAM_WIDTH_BYTES;
  // TODO. as opt can mmap file and just load directly into it.
  alignas(4096)
      uint64_t OUTBUF[this->stream_depth * BridgeConstants::STREAM_WIDTH_BYTES];
  auto bytes_received = pull(this->stream_idx,
                             (char *)OUTBUF,
                             maximum_batch_bytes,
                             minimum_batch_bytes);
  // check that a tracefile exists (one is enough) since the manager
  // does not create a tracefile when trace_enable is disabled, but the
  // TracerV bridge still exists, and no tracefile is created by default.
  if (this->tracefile) {
    if (this->human_readable || this->test_output) {
      for (int i = 0; i < (bytes_received / sizeof(uint64_t)); i += 8) {
        if (this->test_output) {
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 7]);
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 6]);
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 5]);
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 4]);
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 3]);
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 2]);
          fprintf(this->tracefile, "%016lx", OUTBUF[i + 1]);
          fprintf(this->tracefile, "%016lx\n", OUTBUF[i + 0]);
          // At least one valid instruction
        } else {
          for (int q = 0; q < max_core_ipc; q++) {
            if (OUTBUF[i + q + 1] & valid_mask) {
              fprintf(this->tracefile,
                      "Cycle: %016" PRId64 " I%d: %016" PRIx64 "\n",
                      OUTBUF[i + 0],
                      q,
                      OUTBUF[i + q + 1] & (~valid_mask));
            } else {
              break;
            }
          }
        }
      }
    } else if (this->fireperf) {

      for (int i = 0; i < (bytes_received / sizeof(uint64_t)); i += 8) {
        uint64_t cycle_internal = OUTBUF[i + 0];

        for (int q = 0; q < max_core_ipc; q++) {
          if (OUTBUF[i + 1 + q] & valid_mask) {
            uint64_t iaddr =
                (uint64_t)((((int64_t)(OUTBUF[i + 1 + q])) << 24) >> 24);
            this->trace_tracker->addInstruction(iaddr, cycle_internal);
#ifdef FIREPERF_LOGGER
            fprintf(this->tracefile, "%016llx", iaddr);
            fprintf(this->tracefile, "%016llx\n", cycle_internal);
#endif // FIREPERF_LOGGER
          }
        }
      }
    } else {
      for (int i = 0; i < (bytes_received / sizeof(uint64_t)); i += 8) {
        // this stores as raw binary. stored as little endian.
        // e.g. to get the same thing as the human readable above,
        // flip all the bytes in each 512-bit line.
        for (int q = 0; q < 8; q++) {
          fwrite(OUTBUF + (i + q), sizeof(uint64_t), 1, this->tracefile);
        }
      }
    }
  }
  return bytes_received;
}

void tracerv_t::tick() {
  if (this->trace_enabled) {
    process_tokens(this->stream_depth, this->stream_depth);
  }
}

// Pull in any remaining tokens and flush them to file
void tracerv_t::flush() {
  while (this->trace_enabled && (process_tokens(this->stream_depth, 0) > 0))
    ;
}
#endif // TRACERVBRIDGEMODULE_struct_guard
