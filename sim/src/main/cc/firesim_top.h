#ifndef __FIRESIM_TOP_H
#define __FIRESIM_TOP_H

#include "simif.h"
#include "fesvr/firesim_fesvr.h"
#include "endpoints/endpoint.h"
#include "endpoints/fpga_model.h"
#include "endpoints/loadmem_m.h"

class firesim_top_t: virtual simif_t
{
    public:
        firesim_top_t(int argc, char** argv, std::vector<firesim_fesvr_t*> fesvr_vec, uint32_t fesvr_step_size);
        ~firesim_top_t() { }

        void run();
        void loadmem();
        void print_sim_rate();

    protected:
        void add_endpoint(endpoint_t* endpoint) {
            endpoints.push_back(endpoint);
        }

    private:
        std::vector<loadmem_m> loadmem_vec;

        uint64_t start_time;

        // Memory mapped endpoints bound to software models
        std::vector<endpoint_t*> endpoints;
        // FPGA-hosted models with programmable registers & instrumentation
        std::vector<FpgaModel*> fpga_models;
        std::vector<firesim_fesvr_t*> fesvr_vec;
        uint64_t max_cycles;

        // profile interval: # of cycles to advance before profiling instrumentation registers in models
        // This sets the coarse_step_size in loop
        uint64_t profile_interval;
        uint32_t fesvr_step_size;

        // If set, will write all zeros to fpga dram before commencing simulation
        bool do_zero_out_dram = false;
        // Main simulation loop
        // stepsize = number of target cycles between FESVR interactions
        // coarse_step_size = maximum number of target cycles loop may advance the simulator
        void loop(size_t step_size, uint64_t coarse_step_size);

        // Helper functions to handoff fesvr requests to the loadmem unit
        void handle_loadmem_read(fesvr_loadmem_t loadmem);
        void handle_loadmem_write(fesvr_loadmem_t loadmem);
        void serial_bypass_via_loadmem();
};

#endif // __FIRESIM_TOP_H
