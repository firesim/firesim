//See LICENSE for license details
#ifndef __FIRESIM_TOP_H
#define __FIRESIM_TOP_H

#include <memory>

#include "simif.h"
#include "bridges/bridge_driver.h"
#include "bridges/fpga_model.h"
#include "systematic_scheduler.h"

#include "bridges/synthesized_prints.h"

class firesim_top_t: virtual simif_t, public systematic_scheduler_t
{
    public:
        firesim_top_t(int argc, char** argv);
        ~firesim_top_t() { }

        void run();

    protected:
        void add_bridge_driver(bridge_driver_t* bridge_driver) {
            bridges.push_back(std::unique_ptr<bridge_driver_t>(bridge_driver));
        }

    private:
        // A registry of all bridge drivers in the simulator
        std::vector<std::unique_ptr<bridge_driver_t> > bridges;
        // FPGA-hosted models with programmable registers & instrumentation
        // (i.e., bridges_drivers whose tick() is a nop)
        std::vector<FpgaModel*> fpga_models;

        // profile interval: # of cycles to advance before profiling instrumentation registers in models
        uint64_t profile_interval = -1;
        uint64_t profile_models();

        // If set, will write all zeros to fpga dram before commencing simulation
        bool do_zero_out_dram = false;

        // Returns true if any bridge has signaled for simulation termination
        bool simulation_complete();
        // Returns the error code of the first bridge for which it is non-zero
        int exit_code();
};

#endif // __FIRESIM_TOP_H
