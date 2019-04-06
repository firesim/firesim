#ifdef TRACERVWIDGET_struct_guard

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
#define TRACERV_ADDR 0x100000000L

// Layout
// OUTBUF[i+1]  (y)                         OUTBUF[i]  (x)
// +--+-+------------------+-------------+  +------------+-------+-+-+------------+----------------+
// |  |v|   iaddr[39:0]    | insn[31:11] |  | insn[10:0] |pr[2:0]|e|i| cause[7:0] |   tval[39:0]   |
// +--+-+------------------+-------------+  +------------+-------+-+-+------------+----------------+

#define GET_VALID(x,y) ((y >> 61) & 0x1)
#define GET_INST(x,y) (((x >> 53) & 0x7ff) + ((y & 0x1fffff) << 11))
#define GET_IADDR(x,y) ((y >> 21) & 0xffffffffff)
#define GET_PRIV(x,y) ((x >> 50) & 0x7)

tracerv_t::tracerv_t(
    simif_t *sim, std::vector<std::string> &args, TRACERVWIDGET_struct * mmio_addrs, int tracerno) : endpoint_t(sim)
{
    this->mmio_addrs = mmio_addrs;
    const char *tracefilename = NULL;

    this->tracefile = NULL;
    this->start_cycle = 0;
    this->end_cycle = ULONG_MAX;

    std::string num_equals = std::to_string(tracerno) + std::string("=");
    std::string tracefile_arg =         std::string("+tracefile") + num_equals;
    std::string tracestart_arg =         std::string("+trace-start") + num_equals;
    std::string traceend_arg =         std::string("+trace-end") + num_equals;

    for (auto &arg: args) {
        if (arg.find(tracefile_arg) == 0) {
            tracefilename = const_cast<char*>(arg.c_str()) + tracefile_arg.length();
        }
        if (arg.find(tracestart_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + tracestart_arg.length();
            this->start_cycle = atol(str);
        }
        if (arg.find(traceend_arg) == 0) {
            char *str = const_cast<char*>(arg.c_str()) + traceend_arg.length();
            this->end_cycle = atol(str);
        }
    }

    if (tracefilename) {
        this->tracefile = fopen(tracefilename, "w");
        if (!this->tracefile) {
            fprintf(stderr, "Could not open Trace log file: %s\n", tracefilename);
            abort();
        }
    }
}

tracerv_t::~tracerv_t() {
    if (this->tracefile) {
        fclose(this->tracefile);
    }
    free(this->mmio_addrs);
}

void tracerv_t::init() {
    cur_cycle = 0;

    printf("Collect trace from %lu to %lu cycles\n", start_cycle, end_cycle);
}

// defining this stores as human readable hex (e.g. open in VIM)
// undefining this stores as bin (e.g. open with vim hex mode)
#define HUMAN_READABLE

#define START_MARKER	0x12fdc // main start of test-runner.riscv
#define END_MARKER	  0x13100 // main end of test-runner.riscv
//#define START_MARKER 0xe000251d4c
//#define END_MARKER 0xe000251fa8

static bool should_trace = false;

void tracerv_t::tick() {
    uint64_t outfull = read(this->mmio_addrs->tracequeuefull);

    #define QUEUE_DEPTH 6144
    
    uint64_t OUTBUF[QUEUE_DEPTH * 8];
		uint64_t mini_cycle = 0;
    if (outfull) {
        int can_write = cur_cycle >= start_cycle && cur_cycle < end_cycle;

        // TODO. as opt can mmap file and just load directly into it.
        pull(TRACERV_ADDR, (char*)OUTBUF, QUEUE_DEPTH * 64);
        if (this->tracefile && can_write) {
#ifdef HUMAN_READABLE
            for (int i = 0; i < QUEUE_DEPTH * 8; i+=8) {
                // Assume one hart
                int val = GET_VALID(OUTBUF[i], OUTBUF[i+1]);
                uint64_t iaddr = GET_IADDR(OUTBUF[i], OUTBUF[i+1]);
								uint64_t insn = GET_INST(OUTBUF[i], OUTBUF[i+1]);
								int priv = GET_PRIV(OUTBUF[i], OUTBUF[i+1]);

								mini_cycle ++;
                
								if (val && (iaddr == START_MARKER || should_trace)) {
                   if (!should_trace) {
                       should_trace = true;
                       fprintf(this->tracefile, "========================== TRACE START ==========================\n");
                       fprintf(this->tracefile, "Cycle           PC               Instruction     Priv\n");
                   }
                   
									 fprintf(this->tracefile, "%ld, %016llx, %016llx, %d\n", cur_cycle + mini_cycle, iaddr, insn, priv);
								}

                if (val && iaddr == END_MARKER && should_trace) {
                    should_trace = false;
                    fprintf(this->tracefile, "========================== TRACE END ==========================\n");
                }

            }
#else
            for (int i = 0; i < QUEUE_DEPTH * 8; i+=8) {
                // this stores as raw binary. stored as little endian.
                // e.g. to get the same thing as the human readable above,
                // flip all the bytes in each 512-bit line.
                for (int q = 0; q < 8; q++) {
                    fwrite(OUTBUF + (i+q), sizeof(uint64_t), 1, this->tracefile);
                }
            }
#endif
        }
        cur_cycle += QUEUE_DEPTH;
    }
}

#endif // TRACERVWIDGET_struct_guard
