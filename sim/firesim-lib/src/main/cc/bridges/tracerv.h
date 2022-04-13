//See LICENSE for license details
#ifndef __TRACERV_H
#define __TRACERV_H

#include "bridges/bridge_driver.h"
#include "bridges/clock_info.h"
#include <vector>
#include "bridges/tracerv/tracerv_processing.h"
#include "bridges/tracerv/trace_tracker.h"

#ifdef TRACERVBRIDGEMODULE_struct_guard

// Bridge Driver Instantiation Template
#define INSTANTIATE_TRACERV(FUNC,IDX) \
     TRACERVBRIDGEMODULE_ ## IDX ## _substruct_create; \
     FUNC(new tracerv_t( \
        this, \
        args, \
        TRACERVBRIDGEMODULE_ ## IDX ## _substruct, \
        TRACERVBRIDGEMODULE_ ## IDX ## _to_cpu_stream_dma_address, \
        TRACERVBRIDGEMODULE_ ## IDX ## _to_cpu_stream_count_address, \
        TRACERVBRIDGEMODULE_ ## IDX ## _to_cpu_stream_full_address, \
        TRACERVBRIDGEMODULE_ ## IDX ## _max_core_ipc, \
        TRACERVBRIDGEMODULE_ ## IDX ## _clock_domain_name, \
        TRACERVBRIDGEMODULE_ ## IDX ## _clock_multiplier, \
        TRACERVBRIDGEMODULE_ ## IDX ## _clock_divisor, \
        IDX)); \

class tracerv_t: public bridge_driver_t

{
    public:
        tracerv_t(simif_t *sim,
                  std::vector<std::string> &args,
                  TRACERVBRIDGEMODULE_struct * mmio_addrs,
                  const unsigned int dma_address,
                  const unsigned int stream_count_address,
                  const unsigned int stream_full_address,
                  const unsigned int max_core_ipc,
                  const char* const  clock_domain_name,
                  const unsigned int clock_multiplier,
                  const unsigned int clock_divisor,
                  int tracerno);
        ~tracerv_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }
        virtual void finish() { flush(); };

    private:
        TRACERVBRIDGEMODULE_struct * mmio_addrs;
        const unsigned int dma_address;
        const unsigned int stream_count_address;
        const unsigned int stream_full_address;
        const int max_core_ipc;
        ClockInfo clock_info;

        FILE * tracefile;
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
        ObjdumpedBinary * linuxbin;
        TraceTracker * trace_tracker;

        bool human_readable = false;
        // If no filename is provided, the instruction trace is not collected
        // and the bridge drops all tokens to improve FMR
        bool trace_enabled = true;
        // Used in unit testing to check TracerV is correctly pulling instuctions off the target
        bool test_output = false;
        long dma_addr;
        std::string tracefilename;
        std::string dwarf_file_name;
        bool fireperf = false;

        void process_tokens(int num_beats);
        int beats_available_stable();
        void flush();
};
#endif // TRACERVBRIDGEMODULE_struct_guard

#endif // __TRACERV_H
