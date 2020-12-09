//See LICENSE for license details
#ifndef __SERIAL_H
#define __SERIAL_H

#include "bridges/bridge_driver.h"
#include "fesvr/firesim_tsi.h"

template<class T>
struct serial_data_t {
    struct {
        T bits;
        bool valid;
        bool ready;
        bool fire() { return valid && ready; }
    } in;
    struct {
        T bits;
        bool ready;
        bool valid;
        bool fire() { return valid && ready; }
    } out;
};

// Bridge Driver Instantiation Template
// Casts are required for now since the emitted type can change
#define INSTANTIATE_SERIAL(FUNC,IDX) \
    SERIALBRIDGEMODULE_## IDX ##_substruct_create; \
    FUNC(new serial_t(this,\
                      args, \
                      SERIALBRIDGEMODULE_ ## IDX ## _substruct, \
                      IDX, \
                      SERIALBRIDGEMODULE_ ## IDX ## _has_memory, \
                      SERIALBRIDGEMODULE_ ## IDX ## _memory_offset)); \

#ifdef SERIALBRIDGEMODULE_struct_guard
class serial_t: public bridge_driver_t
{
    public:
        serial_t(simif_t* sim, const std::vector<std::string>& args, SERIALBRIDGEMODULE_struct * mmio_addrs, int serialno, bool has_mem, int64_t mem_host_offset);
        ~serial_t();
        virtual void init();
        virtual void tick();
        virtual bool terminate(){ return fesvr->done(); }
        virtual int exit_code(){ return fesvr->exit_code(); }
        virtual void finish() {};

    private:
        SERIALBRIDGEMODULE_struct * mmio_addrs;
        simif_t* sim;
        firesim_tsi_t* fesvr;
        bool has_mem;
        // host memory offset based on the number of memory models and their size
        int64_t mem_host_offset;
        // Number of target cycles between fesvr interactions
        uint32_t step_size;
        // Tell the widget to start enqueuing tokens
        void go();
        // Moves data to and from the widget and fesvr
        void send(); // FESVR -> Widget
        void recv(); // Widget -> FESVR

        // Helper functions to handoff fesvr requests to the loadmem unit
        void handle_loadmem_read(firesim_loadmem_t loadmem);
        void handle_loadmem_write(firesim_loadmem_t loadmem);
        void serial_bypass_via_loadmem();
};
#endif // SERIALBRIDGEMODULE_struct_guard

#endif // __SERIAL_H
