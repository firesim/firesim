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
#include "endpoints/synthesized_assertions.h"

firesim_top_t::firesim_top_t(int argc, char** argv)
{
    std::vector<std::string> args(argv + 1, argv + argc);
    max_cycles = -1;
    profile_interval = max_cycles;

    for (auto &arg: args) {
        if (arg.find("+max-cycles=") == 0) {
            max_cycles = atoi(arg.c_str()+12);
        }
        if (arg.find("+profile-interval=") == 0) {
            profile_interval = atoi(arg.c_str()+18);
        }
        if (arg.find("+zero-out-dram") == 0) {
            do_zero_out_dram = true;
        }
    }


#ifdef UARTWIDGET_struct_guard
    #ifdef UARTWIDGET_0_PRESENT
    UARTWIDGET_0_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_0_substruct, 0));
    #endif
    #ifdef UARTWIDGET_1_PRESENT
    UARTWIDGET_1_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_1_substruct, 1));
    #endif
    #ifdef UARTWIDGET_2_PRESENT
    UARTWIDGET_2_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_2_substruct, 2));
    #endif
    #ifdef UARTWIDGET_3_PRESENT
    UARTWIDGET_3_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_3_substruct, 3));
    #endif
#endif


#ifdef NASTIWIDGET_0
    endpoints.push_back(new sim_mem_t(this, argc, argv));
#endif

std::vector<uint64_t> host_mem_offsets;
uint64_t host_mem_offset = -0x80000000LL;
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
                argc, argv, "memory_stats.csv", host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += (1ULL << MEMMODEL_0_target_addr_bits);
#endif

#ifdef MEMMODEL_1
    fpga_models.push_back(new FpgaMemoryModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_1_R_num_registers,
                    (const unsigned int*) MEMMODEL_1_R_addrs,
                    (const char* const*) MEMMODEL_1_R_names,
                    MEMMODEL_1_W_num_registers,
                    (const unsigned int*) MEMMODEL_1_W_addrs,
                    (const char* const*) MEMMODEL_1_W_names),
                argc, argv, "memory_stats.csv", host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_1_target_addr_bits;
#endif

#ifdef MEMMODEL_2
    fpga_models.push_back(new FpgaMemoryModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_2_R_num_registers,
                    (const unsigned int*) MEMMODEL_2_R_addrs,
                    (const char* const*) MEMMODEL_2_R_names,
                    MEMMODEL_2_W_num_registers,
                    (const unsigned int*) MEMMODEL_2_W_addrs,
                    (const char* const*) MEMMODEL_2_W_names),
                argc, argv, "memory_stats.csv", host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_2_target_addr_bits;
#endif

#ifdef MEMMODEL_3
    fpga_models.push_back(new FpgaMemoryModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_3_R_num_registers,
                    (const unsigned int*) MEMMODEL_3_R_addrs,
                    (const char* const*) MEMMODEL_3_R_names,
                    MEMMODEL_3_W_num_registers,
                    (const unsigned int*) MEMMODEL_3_W_addrs,
                    (const char* const*) MEMMODEL_3_W_names),
                argc, argv, "memory_stats.csv", host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_3_target_addr_bits;
#endif

    // TODO: Serial multiple copy support
#ifdef SERIALWIDGET_struct_guard
    SERIALWIDGET_0_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_0_substruct, 0, host_mem_offsets[0]));
#endif


#ifdef BLOCKDEVWIDGET_struct_guard
    #ifdef BLOCKDEVWIDGET_0_PRESENT
    BLOCKDEVWIDGET_0_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_0_num_trackers, BLOCKDEVWIDGET_0_latency_bits, BLOCKDEVWIDGET_0_substruct, 0));
    #endif
    #ifdef BLOCKDEVWIDGET_1_PRESENT
    BLOCKDEVWIDGET_1_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_1_num_trackers, BLOCKDEVWIDGET_1_latency_bits, BLOCKDEVWIDGET_1_substruct, 1));
    #endif
    #ifdef BLOCKDEVWIDGET_2_PRESENT
    BLOCKDEVWIDGET_2_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_2_num_trackers, BLOCKDEVWIDGET_2_latency_bits, BLOCKDEVWIDGET_2_substruct, 2));
    #endif
    #ifdef BLOCKDEVWIDGET_3_PRESENT
    BLOCKDEVWIDGET_3_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_3_num_trackers, BLOCKDEVWIDGET_3_latency_bits, BLOCKDEVWIDGET_3_substruct, 3));
    #endif
#endif

#ifdef SIMPLENICWIDGET_struct_guard
    #ifdef SIMPLENICWIDGET_0_PRESENT
    SIMPLENICWIDGET_0_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_0_substruct, 0, SIMPLENICWIDGET_0_DMA_ADDR));
    #endif
    #ifdef SIMPLENICWIDGET_1_PRESENT
    SIMPLENICWIDGET_1_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_1_substruct, 1, SIMPLENICWIDGET_1_DMA_ADDR));
    #endif
    #ifdef SIMPLENICWIDGET_2_PRESENT
    SIMPLENICWIDGET_2_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_2_substruct, 2, SIMPLENICWIDGET_2_DMA_ADDR));
    #endif
    #ifdef SIMPLENICWIDGET_3_PRESENT
    SIMPLENICWIDGET_3_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_3_substruct, 3, SIMPLENICWIDGET_3_DMA_ADDR));
    #endif
#endif

#ifdef TRACERVWIDGET_struct_guard
    #ifdef TRACERVWIDGET_0_PRESENT
    TRACERVWIDGET_0_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_0_substruct, 0, TRACERVWIDGET_0_DMA_ADDR));
    #endif
    #ifdef TRACERVWIDGET_1_PRESENT
    TRACERVWIDGET_1_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_1_substruct, 1, TRACERVWIDGET_1_DMA_ADDR));
    #endif
    #ifdef TRACERVWIDGET_2_PRESENT
    TRACERVWIDGET_2_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_2_substruct, 2, TRACERVWIDGET_2_DMA_ADDR));
    #endif
    #ifdef TRACERVWIDGET_3_PRESENT
    TRACERVWIDGET_3_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_3_substruct, 3, TRACERVWIDGET_3_DMA_ADDR));
    #endif
#endif

    // add more endpoints here
#ifdef ASSERTIONWIDGET_struct_guard
    #ifdef ASSERTIONWIDGET_0_PRESENT
    ASSERTIONWIDGET_0_substruct_create;
    endpoints.push_back(new synthesized_assertions_t(this, ASSERTIONWIDGET_0_substruct));
    #endif
#endif
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

