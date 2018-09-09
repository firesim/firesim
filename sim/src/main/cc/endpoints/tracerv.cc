#include "tracerv.h"

#include <stdio.h>
#include <string.h>

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



tracerv_t::tracerv_t(simif_t *sim, char *tracefile): endpoint_t(sim)
{
#ifdef TRACERVWIDGET_0
    this->tracefile = fopen(tracefile, "w");
    if (!this->tracefile) {
        fprintf(stderr, "Could not open Trace log file: %s\n", this->tracefile);
        abort();
    }
#endif // #ifdef TRACERVWIDGET_0
}

tracerv_t::~tracerv_t() {
#ifdef TRACERVWIDGET_0
    if (this->tracefile) {
        fclose(this->tracefile);
    }
#endif // #ifdef TRACERVWIDGET_0
}

void tracerv_t::init() {
}

bool tracerv_t::done() {
#ifdef TRACERVWIDGET_0
//    return read(TRACERVWIDGET_0(done));
    return true;
#else
    return true;
#endif
}

void tracerv_t::tick() {
#ifdef TRACERVWIDGET_0
    uint64_t outfull = read(TRACERVWIDGET_0(tracequeuefull));

    #define QUEUE_DEPTH 6144
    
    uint64_t OUTBUF[QUEUE_DEPTH * 8];

    if (outfull) {
        // TODO. as opt can mmap file and just load directly into it.
        pull(0x0, (char*)OUTBUF, QUEUE_DEPTH * 64);
        for (int i = 0; i < QUEUE_DEPTH * 8; i+=8) {
            fprintf(this->tracefile, "%016llx", OUTBUF[i+7]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+6]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+5]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+4]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+3]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+2]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+1]);
            fprintf(this->tracefile, "%016llx\n", OUTBUF[i+0]);
        }
    }

#endif // ifdef TRACERVWIDGET_0
}
