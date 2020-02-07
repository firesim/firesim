//See LICENSE for license details
#ifndef __TRACERV_H
#define __TRACERV_H

#include "bridges/bridge_driver.h"
#include <vector>
#include "bridges/tracerv/tracerv_processing.h"
#include "bridges/tracerv/trace_tracker.h"

// TODO: get this automatically
#define NUM_CORES 1

#ifdef TRACERVBRIDGEMODULE_struct_guard
class tracerv_t: public bridge_driver_t

{
    public:
        tracerv_t(simif_t *sim, std::vector<std::string> &args,
        TRACERVBRIDGEMODULE_struct * mmio_addrs, int tracervno, long dma_addr);
        ~tracerv_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }
        virtual void finish() { flush(); };

    private:
        TRACERVBRIDGEMODULE_struct * mmio_addrs;
        simif_t* sim;
        FILE * tracefiles[NUM_CORES];
        uint64_t cur_cycle;
        uint64_t trace_trigger_start, trace_trigger_end;
        uint32_t trigger_selector;

        // TODO: rename this from linuxbin
        ObjdumpedBinary * linuxbin;
        TraceTracker * trace_trackers[NUM_CORES];

        bool human_readable = false;
        // Used in unit testing to check TracerV is correctly pulling instuctions off the target
        bool test_output = false;
        long dma_addr;
        void flush();
        int beats_available_stable();
        std::string tracefilename;
        std::string dwarf_file_name;
        bool fireperf = false;
};
#endif // TRACERVBRIDGEMODULE_struct_guard

#endif // __TRACERV_H
