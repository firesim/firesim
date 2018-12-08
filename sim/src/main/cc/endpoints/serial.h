#ifndef __SERIAL_H
#define __SERIAL_H

#include "endpoints/endpoint.h"
#include "fesvr/firesim_fesvr.h"

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

#ifdef SERIALWIDGET_struct_guard
class serial_t: public endpoint_t
{
    public:
        serial_t(simif_t* sim, const std::vector<std::string>& args, SERIALWIDGET_struct * mmio_addrs, int serialno, uint64_t mem_host_offset);
        ~serial_t();
        virtual void init();
        virtual void tick();
        virtual bool terminate(){ return fesvr->done(); }
        virtual int exit_code(){ return fesvr->exit_code(); }

    private:
        SERIALWIDGET_struct * mmio_addrs;
        simif_t* sim;
        firesim_fesvr_t* fesvr;
        // host memory offset based on the number of memory models and their size
        uint64_t mem_host_offset;
        // Number of target cycles between fesvr interactions
        uint32_t step_size;
        // Tell the widget to start enqueuing tokens
        void go();
        // Moves data to and from the widget and fesvr
        void send(); // FESVR -> Widget
        void recv(); // Widget -> FESVR

        // Helper functions to handoff fesvr requests to the loadmem unit
        void handle_loadmem_read(fesvr_loadmem_t loadmem);
        void handle_loadmem_write(fesvr_loadmem_t loadmem);
        void serial_bypass_via_loadmem();
};
#endif // SERIALWIDGET_struct_guard

#endif // __SERIAL_H
