#ifndef __FIRESIM_TOP_H
#define __FIRESIM_TOP_H

#include "simif.h"
#include "fesvr/firesim_fesvr.h"
#include "endpoints/endpoint.h"
#include "endpoints/fpga_model.h"

class firesim_top_t: virtual simif_t
{
    public:
        firesim_top_t(int argc, char** argv, firesim_fesvr_t* fesvr);
        ~firesim_top_t() { }

        void run(size_t step_size);
        void tether_bypass_via_loadmem();

    protected:
        void add_endpoint(endpoint_t* endpoint) {
            endpoints.push_back(endpoint);
        }

    private:
        // Memory mapped endpoints bound to software models
        std::vector<endpoint_t*> endpoints;
        // FPGA-hosted models with programmable registers & instrumentation
        std::vector<FpgaModel*> fpga_models;
        firesim_fesvr_t* fesvr;
        uint64_t max_cycles;

        // profile interval: # of cycles to advance before profiling instrumentation registers in models
        // This sets the coarse_step_size in loop
        uint64_t profile_interval;

        // If set, will write all zeros to fpga dram before commencing simulation
        bool do_zero_out_dram = false;
        // Main simulation loop
        // stepsize = number of target cycles between FESVR interactions
        // coarse_step_size = maximum number of target cycles loop may advance the simulator
        void loop(size_t step_size, uint64_t coarse_step_size);

        // Helper functions to handoff fesvr requests to the loadmem unit
        void handle_loadmem_read(fesvr_loadmem_t loadmem);
        void handle_loadmem_write(fesvr_loadmem_t loadmem);
};

#endif // __FIRESIM_TOP_H
