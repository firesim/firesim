#include <limits.h>

#include "firesim_top.h"

// FireSim-defined endpoints
#include "endpoints/serial.h"
#include "endpoints/uart.h"
#include "endpoints/simplenic.h"
#include "endpoints/blockdev.h"
#include "endpoints/tracerv.h"
// MIDAS-defined endpoints
#include "endpoints/fpga_model.h"
#include "endpoints/sim_mem.h"
#include "endpoints/fpga_memory_model.h"

firesim_top_t::firesim_top_t(int argc, char** argv)
{
    // fields to populate to pass to endpoints
    char * niclogfile = NULL;
    char * slotid = NULL;
    char * tracefile = NULL;
    char * shmemportname = NULL;
    uint64_t mac_little_end = 0; // default to invalid mac addr, force user to specify one
    uint64_t trace_start = 0, trace_end = ULONG_MAX;
    int netbw = MAX_BANDWIDTH, netburst = 8;
    int linklatency = 0;
    bool nic_loopback = false;

    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
        if (arg.find("+max-cycles=") == 0) {
            max_cycles = atoi(arg.c_str()+12);
        }
        if (arg.find("+profile-interval=") == 0) {
            profile_interval = atoi(arg.c_str()+18);
        }
        if (arg.find("+niclog=") == 0) {
            niclogfile = const_cast<char*>(arg.c_str()) + 8;
        }
        if (arg.find("+nic-loopback") == 0) {
            nic_loopback = true;
        }
        if (arg.find("+slotid=") == 0) {
            slotid = const_cast<char*>(arg.c_str()) + 8;
        }

        // TODO: move this and a bunch of other NIC arg parsing into the nic endpoint code itself
        if (arg.find("+shmemportname=") == 0) {
            shmemportname = const_cast<char*>(arg.c_str()) + 15;
        }

        if (arg.find("+zero-out-dram") == 0) {
            do_zero_out_dram = true;
        }
        if (arg.find("+macaddr=") == 0) {
            uint8_t mac_bytes[6];
            int mac_octets[6];
            char * macstring = NULL;
            macstring = const_cast<char*>(arg.c_str()) + 9;
            char * trailingjunk;

            // convert mac address from string to 48 bit int
            if (6 == sscanf(macstring, "%x:%x:%x:%x:%x:%x%c",
                        &mac_octets[0], &mac_octets[1], &mac_octets[2],
                        &mac_octets[3], &mac_octets[4], &mac_octets[5],
                        trailingjunk)) {

                for (int i = 0; i < 6; i++) {
                    mac_little_end |= (((uint64_t)(uint8_t)mac_octets[i]) << (8*i));
                }
            } else {
                fprintf(stderr, "INVALID MAC ADDRESS SUPPLIED WITH +macaddr=\n");
            }
        }
        if (arg.find("+netbw=") == 0) {
            char *str = const_cast<char*>(arg.c_str()) + 7;
            netbw = atoi(str);
        }
        if (arg.find("+netburst=") == 0) {
            char *str = const_cast<char*>(arg.c_str()) + 10;
            netburst = atoi(str);
        }
        if (arg.find("+linklatency=") == 0) {
            char *str = const_cast<char*>(arg.c_str()) + 13;
            linklatency = atoi(str);
        }
        if (arg.find("+tracefile=") == 0) {
            tracefile = const_cast<char*>(arg.c_str()) + 11;
        }
        if (arg.find("+trace-start=") == 0) {
            char *str = const_cast<char*>(arg.c_str()) + 13;
            trace_start = atol(str);
        }
        if (arg.find("+trace-end=") == 0) {
            char *str = const_cast<char*>(arg.c_str()) + 11;
            trace_end = atol(str);
        }
    }

    add_endpoint(new uart_t(this));
    add_endpoint(new serial_t(this, args));

#ifdef NASTIWIDGET_0
    endpoints.push_back(new sim_mem_t(this, argc, argv));
#endif

#ifdef MEMMODEL_0
    fpga_models.push_back(new FpgaMemoryModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_0_R_num_registers,
                    (const unsigned int*) MEMMODEL_0_R_addrs,
                    (const char* const*) MEMMODEL_0_R_names,
                    MEMMODEL_0_W_num_registers,
                    (const unsigned int*) MEMMODEL_0_W_addrs,
                    (const char* const*) MEMMODEL_0_W_names),
                argc, argv, "memory_stats.csv"));
#endif

    add_endpoint(new blockdev_t(this, args));
    add_endpoint(new simplenic_t(this, slotid, mac_little_end, netbw, netburst, linklatency, niclogfile, nic_loopback, shmemportname));
    add_endpoint(new tracerv_t(this, tracefile, trace_start, trace_end));
    // add more endpoints here

    // Add functions you'd like to periodically invoke on a paused simulator here.
    if (profile_interval != -1) {
        register_task([this](){ return this->profile_models();}, 0);
    }
}

bool firesim_top_t::simulation_complete() {
    bool is_complete = false;
    for (auto e: endpoints) {
        is_complete |= e->terminate();
    }
    return is_complete;
}

uint64_t firesim_top_t::profile_models(){
    for (auto mod: fpga_models) {
        mod->profile();
    }
    return profile_interval;
}

int firesim_top_t::exit_code(){
    for (auto e: endpoints) {
        if (e->exit_code())
            return e->exit_code();
    }
    return 0;
}


void firesim_top_t::run() {
    for (auto e: fpga_models) {
        e->init();
    }

    for (auto e: endpoints) {
        e->init();
    }

    if (do_zero_out_dram) {
        fprintf(stderr, "Zeroing out FPGA DRAM. This will take a few seconds...\n");
        zero_out_dram();
    }
    fprintf(stderr, "Commencing simulation.\n");
    uint64_t start_hcycle = hcycle();
    uint64_t start_time = timestamp();

    // Assert reset T=0 -> 50
    target_reset(0, 50);

    while (!simulation_complete() && !has_timed_out()) {
        run_scheduled_tasks();
        step(get_largest_stepsize(), false);
        while(!done() && !simulation_complete()){
            for (auto e: endpoints) e->tick();
        }
    }

    uint64_t end_time = timestamp();
    uint64_t end_cycle = actual_tcycle();
    uint64_t hcycles = hcycle() - start_hcycle;
    double sim_time = diff_secs(end_time, start_time);
    double sim_speed = ((double) end_cycle) / (sim_time * 1000.0);
    // always print a newline after target's output
    fprintf(stderr, "\n");
    int exitcode = exit_code();
    if (exitcode) {
        fprintf(stderr, "*** FAILED *** (code = %d) after %llu cycles\n", exitcode, end_cycle);
    } else if (!simulation_complete() && has_timed_out()) {
        fprintf(stderr, "*** FAILED *** (timeout) after %llu cycles\n", end_cycle);
    } else {
        fprintf(stderr, "*** PASSED *** after %llu cycles\n", end_cycle);
    }
    if (sim_speed > 1000.0) {
        fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f MHz\n", sim_time, sim_speed / 1000.0);
    } else {
        fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f KHz\n", sim_time, sim_speed);
    }
    double fmr = ((double) hcycles / end_cycle);
    fprintf(stderr, "FPGA-Cycles-to-Model-Cycles Ratio (FMR): %.2f\n", fmr);
    expect(!exitcode, NULL);

    for (auto e: fpga_models) {
        e->finish();
    }
}

