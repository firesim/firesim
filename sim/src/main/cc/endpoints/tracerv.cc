#include "tracerv.h"

#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

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
    //fprintf(power_file, "outfull: %d\n", outfull);

    #define QUEUE_DEPTH 6144
    
    uint64_t OUTBUF[QUEUE_DEPTH * 8];

    if (outfull) {
        pull(0x0, (char*)OUTBUF, QUEUE_DEPTH * 64);
        for (int i = 0; i < QUEUE_DEPTH * 8; i+=8) {
/*            if (i % 8 == 0) {
                fprintf(this->tracefile, "%lld,", (OUTBUF[i] >> 7) & 0xFFFFFFFF);
                fprintf(this->tracefile, "%lld,", (OUTBUF[i] >> 4) & 0x7);
                fprintf(this->tracefile, "%lld,", (OUTBUF[i] >> 1) & 0x7);
                fprintf(this->tracefile, "%lld\n", (OUTBUF[i]) & 0x1);

            }*/

//            fprintf(this->tracefile, "%016llx", OUTBUF[i+7]);
//            fprintf(this->tracefile, "%016llx", OUTBUF[i+6]);
//            fprintf(this->tracefile, "%016llx", OUTBUF[i+5]);
//            fprintf(this->tracefile, "%016llx", OUTBUF[i+4]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+3]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+2]);
            fprintf(this->tracefile, "%016llx", OUTBUF[i+1]);
            fprintf(this->tracefile, "%016llx\n", OUTBUF[i+0]);
        }
    }




#endif // ifdef TRACERVWIDGET_0
}
