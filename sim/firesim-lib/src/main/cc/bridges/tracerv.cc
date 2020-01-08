//See LICENSE for license details
#ifdef TRACERVBRIDGEMODULE_struct_guard

#include "tracerv.h"

#include <stdio.h>
#include <string.h>
#include <limits.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

// TODO: generate a header with these automatically

// bitwidths for stuff in the trace. assume this order too.
#define VALID_WID 1
#define IADDR_WID 40
#define INSN_WID 32
#define PRIV_WID 3
#define EXCP_WID 1
#define INT_WID 1
#define CAUSE_WID 8
#define TVAL_WID 40
#define TOTAL_WID (VALID_WID + IADDR_WID + INSN_WID + PRIV_WID + EXCP_WID + INT_WID + CAUSE_WID + TVAL_WID)

// The maximum number of beats available in the FPGA-side FIFO
#define QUEUE_DEPTH 6144

tracerv_t::tracerv_t(
    simif_t *sim, std::vector<std::string> &args, TRACERVBRIDGEMODULE_struct * mmio_addrs, int tracerno, long dma_addr) : bridge_driver_t(sim)
{
    static_assert(NUM_CORES <= 7, "TRACERV CURRENTLY ONLY SUPPORT <= 7 Cores");
    this->mmio_addrs = mmio_addrs;
    this->dma_addr = dma_addr;
    const char *tracefilename = NULL;

    for (int i = 0; i < NUM_CORES; i++) {
         this->tracefiles[i] = NULL;
    }

    this->trace_trigger_start = 0;
    this->trace_trigger_end = ULONG_MAX;
    this->trigger_selector = 0;
    this->tracefilename = "";

    std::string num_equals = std::to_string(tracerno) + std::string("=");
    std::string tracefile_arg =        std::string("+tracefile") + num_equals;
    std::string tracestart_arg =       std::string("+trace-start") + num_equals;
    std::string traceend_arg =         std::string("+trace-end") + num_equals;
    std::string traceselect_arg =         std::string("+trace-select") + num_equals;
    // Testing: provides a reference file to diff the collected trace against
    std::string testoutput_arg =         std::string("+trace-test-output") + std::to_string(tracerno);
    // Formats the output before dumping the trace to file
    std::string humanreadable_arg =    std::string("+trace-humanreadable") + std::to_string(tracerno);

    for (auto &arg: args) {
        if (arg.find(tracefile_arg) == 0) {
            tracefilename = const_cast<char*>(arg.c_str()) + tracefile_arg.length();
            this->tracefilename = std::string(tracefilename);
        }
        if (arg.find(traceselect_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + traceselect_arg.length();
            this->trigger_selector = atol(str);
        }
        if (arg.find(tracestart_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + tracestart_arg.length();
            char * pEnd;
            this->trace_trigger_start = trigger_selector==1 ? atol(str) : strtoul (str,&pEnd,16);
        }
        if (arg.find(traceend_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + traceend_arg.length();
            char * pEnd;
            this->trace_trigger_end = trigger_selector==1 ? atol(str) : strtoul (str,&pEnd,16);
        }
        if (arg.find(testoutput_arg) == 0) {
            this->test_output = true;
        }
        if (arg.find(humanreadable_arg) == 0) {
            this->human_readable = true;
        }


    }

    if (tracefilename) {
        // giving no tracefilename means we will create NO tracefiles
        for (int i = 0; i < NUM_CORES; i++) {
            std::string tfname = std::string(tracefilename) + std::to_string(i);
            this->tracefiles[i] = fopen(tfname.c_str(), "w");
            if (!this->tracefiles[i]) {
                fprintf(stderr, "Could not open Trace log file: %s\n", tracefilename);
                abort();
            }
        }
    } else {
        fprintf(stderr, "TraceRV: Warning: No +tracefileN given!\n");
    }
}

tracerv_t::~tracerv_t() {
    for (int i = 0; i < NUM_CORES; i++) {
        if (this->tracefiles[i]) {
            fclose(this->tracefiles[i]);
        }
    } 
    free(this->mmio_addrs);
}

void tracerv_t::init() {
    if (this->trigger_selector == 1)
    {
      write(this->mmio_addrs->triggerSelector, this->trigger_selector);
      write(this->mmio_addrs->hostTriggerCycleCountStartHigh, this->trace_trigger_start >> 32);
      write(this->mmio_addrs->hostTriggerCycleCountStartLow, this->trace_trigger_start & ((1ULL << 32) - 1));
      write(this->mmio_addrs->hostTriggerCycleCountEndHigh, this->trace_trigger_end >> 32);
      write(this->mmio_addrs->hostTriggerCycleCountEndLow, this->trace_trigger_end & ((1ULL << 32) - 1));
      printf("TracerV: Collect trace from %lu to %lu cycles\n", trace_trigger_start, trace_trigger_end);
    }
    else if (this->trigger_selector == 2)
    {
      write(this->mmio_addrs->triggerSelector, this->trigger_selector);
      write(this->mmio_addrs->hostTriggerPCStartHigh, this->trace_trigger_start >> 32);
      write(this->mmio_addrs->hostTriggerPCStartLow, this->trace_trigger_start & ((1ULL << 32) - 1));
      write(this->mmio_addrs->hostTriggerPCEndHigh, this->trace_trigger_end >> 32);
      write(this->mmio_addrs->hostTriggerPCEndLow, this->trace_trigger_end & ((1ULL << 32) - 1));
      printf("TracerV: Collect trace from instruction address %lx to %lx\n", trace_trigger_start, trace_trigger_end);
    }
    else if (this->trigger_selector == 3)
    {
      write(this->mmio_addrs->triggerSelector, this->trigger_selector);
      write(this->mmio_addrs->hostTriggerStartInst, this->trace_trigger_start & ((1ULL << 32) - 1));
      write(this->mmio_addrs->hostTriggerStartInstMask, this->trace_trigger_start >> 32);
      write(this->mmio_addrs->hostTriggerEndInst, this->trace_trigger_end & ((1ULL << 32) - 1));
      write(this->mmio_addrs->hostTriggerEndInstMask, this->trace_trigger_end >> 32);
      printf("TracerV: Collect trace with start trigger instruction %x masked with %x, and end trigger instruction %x masked with %x\n",
              this->trace_trigger_start & ((1ULL << 32) - 1), this->trace_trigger_start >> 32,
              this->trace_trigger_end & ((1ULL << 32) - 1), this->trace_trigger_end >> 32);
    }
    else
    {
      write(this->mmio_addrs->triggerSelector, this->trigger_selector);
      printf("TracerV: Collecting trace from %lu to %lu cycles\n", trace_trigger_start, trace_trigger_end);
    }
}

// defining this stores as human readable hex (e.g. open in VIM)
// undefining this stores as bin (e.g. open with vim hex mode)

void tracerv_t::tick() {
    uint64_t outfull = read(this->mmio_addrs->tracequeuefull);

    alignas(4096) uint64_t OUTBUF[QUEUE_DEPTH * 8];

    if (outfull) {
        // TODO. as opt can mmap file and just load directly into it.
        pull(dma_addr, (char*)OUTBUF, QUEUE_DEPTH * 64);
        //check that a tracefile exists (one is enough) since the manager
        //does not create a tracefile when trace_enable is disabled, but the
        //TracerV bridge still exists, and no tracefiles are create be default.
        if (this->tracefiles[0]) {
            if (this->human_readable || this->test_output) {
                for (int i = 0; i < QUEUE_DEPTH * 8; i+=8) {
                    if (this->test_output) {
                        fprintf(this->tracefiles[0], "TRACEPORT: ");
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+7]);
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+6]);
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+5]);
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+4]);
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+3]);
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+2]);
                        fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+1]);
                        fprintf(this->tracefiles[0], "%016lx\n", OUTBUF[i+0]);
                    } else {
                        for (int q = 0; q < NUM_CORES; q++) {
                           if ((OUTBUF[i+0+q] >> 40) & 0x1) {
                             fprintf(this->tracefiles[q], "TRACEPORT C%d: %016llx, cycle: %016llx\n", q, OUTBUF[i+0+q], OUTBUF[i+7]);
                           }
                        }
                    }
                }
            } else {
                for (int i = 0; i < QUEUE_DEPTH * 8; i+=8) {
                    // this stores as raw binary. stored as little endian.
                    // e.g. to get the same thing as the human readable above,
                    // flip all the bytes in each 512-bit line.
                    for (int q = 0; q < 8; q++) {
                        fwrite(OUTBUF + (i+q), sizeof(uint64_t), 1, this->tracefiles[0]);
                    }
                }
            }
        }
    }
}


int tracerv_t::beats_available_stable() {
  size_t prev_beats_available = 0;
  size_t beats_available = read(mmio_addrs->outgoing_count);
  while (beats_available > prev_beats_available) {
    prev_beats_available = beats_available;
    beats_available = read(mmio_addrs->outgoing_count);
  }
  return beats_available;
}


// Pull in any remaining tokens and flush them to file
// WARNING: may not function correctly if the simulator is actively running
void tracerv_t::flush() {

    alignas(4096) uint64_t OUTBUF[QUEUE_DEPTH * 8];
    size_t beats_available = beats_available_stable();

    // TODO. as opt can mmap file and just load directly into it.
    pull(dma_addr, (char*)OUTBUF, beats_available * 64);
    //check that a tracefile exists (one is enough) since the manager
    //does not create a tracefile when trace_enable is disabled, but the
    //TracerV bridge still exists, and no tracefiles are create be default.
    if (this->tracefiles[0]) {
        if (this->human_readable || this->test_output) {
            for (int i = 0; i < beats_available * 8; i+=8) {

                if (this->test_output) {
                    fprintf(this->tracefiles[0], "TRACEPORT: ");
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+7]);
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+6]);
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+5]);
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+4]);
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+3]);
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+2]);
                    fprintf(this->tracefiles[0], "%016lx", OUTBUF[i+1]);
                    fprintf(this->tracefiles[0], "%016lx\n", OUTBUF[i+0]);
                } else {
                    for (int q = 0; q < NUM_CORES; q++) {
                      if ((OUTBUF[i+0+q] >> 40) & 0x1) {
                        fprintf(this->tracefiles[q], "TRACEPORT C%d: %016llx, cycle: %016llx\n", q, OUTBUF[i+0+q], OUTBUF[i+7]);
                      }
                    }
                }
            }
        } else {
            for (int i = 0; i < beats_available * 8; i+=8) {
                // this stores as raw binary. stored as little endian.
                // e.g. to get the same thing as the human readable above,
                // flip all the bytes in each 512-bit line.
                for (int q = 0; q < 8; q++) {
                    fwrite(OUTBUF + (i+q), sizeof(uint64_t), 1, this->tracefiles[0]);
                }
            }
        }
    }
}
#endif // TRACERVBRIDGEMODULE_struct_guard
