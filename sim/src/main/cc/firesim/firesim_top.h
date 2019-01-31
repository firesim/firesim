#ifndef __FIRESIM_TOP_H
#define __FIRESIM_TOP_H

#include <memory>

#include "simif.h"
#include "endpoints/endpoint.h"
#include "endpoints/fpga_model.h"
#include "systematic_scheduler.h"

#include "endpoints/synthesized_prints.h"

class firesim_top_t: virtual simif_t, public systematic_scheduler_t
{
    public:
        firesim_top_t(int argc, char** argv);
        ~firesim_top_t() { }

        void run();

    protected:
        void add_endpoint(endpoint_t* endpoint) {
            endpoints.push_back(std::unique_ptr<endpoint_t>(endpoint));
        }

    private:
        // Memory mapped endpoints bound to software models
        std::vector<std::unique_ptr<endpoint_t> > endpoints;
        // FPGA-hosted models with programmable registers & instrumentation
        std::vector<FpgaModel*> fpga_models;

#ifdef PRINTWIDGET_struct_guard
        synthesized_prints_t * print_endpoint;
#endif

        // profile interval: # of cycles to advance before profiling instrumentation registers in models
        uint64_t profile_interval = -1;
        uint64_t profile_models();

        // If set, will write all zeros to fpga dram before commencing simulation
        bool do_zero_out_dram = false;

        // Returns true if any endpoint has signaled for simulation termination
        bool simulation_complete();
        // Returns the error code of the first endpoint for which it is non-zero
        int exit_code();

};

#endif // __FIRESIM_TOP_H
