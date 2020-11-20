#ifndef __HOST_WATCHDOG_H
#define __HOST_WATCHDOG_H

#include <fstream>
#include "bridge_driver.h"

// Periodically checks that the target is advancing by polling an FPGA-hosted
// cycle count, which it writes out to a file.
//
// The causes of an apparently hung simulator can be coarsely deduced from the
// behavior of this class and whether thhe simulation terminates. The three
// most common cases are:
//
//  1) The target itself has hung. Simulation time will continue advance (will
//  show in output log) but the simulator will never terminate
//
//  2) Token starvation: some part of the simulator has not produced a token,
//  or a token has been lost. The simulator hot loop continues to spin, and
//  host_watchdog_t will call for termination after <polling_interval> iterations.
//
//  3) Driver-side deadlock: another bridge driver has locked up in its tick()
//  function, preventing host_watchdog_t::tick() from being called again. Here,
//  the output log will not be updated further, nor will the simulation
//  terminate

class host_watchdog_t: public bridge_driver_t
{

    public:
        host_watchdog_t(simif_t* sim, std::vector<std::string> &args);
        virtual void init() {};
        virtual void tick();
        virtual bool terminate() { return  has_timed_out; };
        virtual int exit_code() { return (has_timed_out) ? 1 : 0; };
        virtual void finish() {};
    private:
        std::ofstream log;
        simif_t * sim;
        time_t start_time;

        bool has_timed_out = false;
        // Arbitrary selection; O(10) wallclock seconds for default targets during linux boot
        const uint64_t polling_interval = 10e5;
        uint64_t trip_count = 0;
        uint64_t last_cycle = 0;
};

#endif //__HOST_WATCHDOG_H
