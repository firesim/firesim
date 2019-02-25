#include "firesim_top.h"

// FireSim-defined endpoints
#include "endpoints/serial.h"
#include "endpoints/uart.h"
#include "endpoints/simplenic.h"
#include "endpoints/blockdev.h"
#include "endpoints/tracerv.h"
// MIDAS-defined endpoints
#include "endpoints/fpga_model.h"
#include "endpoints/fased_memory_timing_model.h"
#include "endpoints/synthesized_assertions.h"
#include "endpoints/synthesized_prints.h"

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
    #ifdef UARTWIDGET_4_PRESENT
    UARTWIDGET_4_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_4_substruct, 4));
    #endif
    #ifdef UARTWIDGET_5_PRESENT
    UARTWIDGET_5_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_5_substruct, 5));
    #endif
    #ifdef UARTWIDGET_6_PRESENT
    UARTWIDGET_6_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_6_substruct, 6));
    #endif
    #ifdef UARTWIDGET_7_PRESENT
    UARTWIDGET_7_substruct_create;
    add_endpoint(new uart_t(this, UARTWIDGET_7_substruct, 7));
    #endif
#endif

std::vector<uint64_t> host_mem_offsets;
uint64_t host_mem_offset = -0x80000000LL;
#ifdef MEMMODEL_0
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_0_R_num_registers,
                    (const unsigned int*) MEMMODEL_0_R_addrs,
                    (const char* const*) MEMMODEL_0_R_names,
                    MEMMODEL_0_W_num_registers,
                    (const unsigned int*) MEMMODEL_0_W_addrs,
                    (const char* const*) MEMMODEL_0_W_names),
                argc, argv, "memory_stats.csv", 1L << TARGET_MEM_ADDR_BITS , host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += (1ULL << MEMMODEL_0_target_addr_bits);
#endif

#ifdef MEMMODEL_1
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_1_R_num_registers,
                    (const unsigned int*) MEMMODEL_1_R_addrs,
                    (const char* const*) MEMMODEL_1_R_names,
                    MEMMODEL_1_W_num_registers,
                    (const unsigned int*) MEMMODEL_1_W_addrs,
                    (const char* const*) MEMMODEL_1_W_names),
                argc, argv, "memory_stats1.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_1_target_addr_bits;
#endif

#ifdef MEMMODEL_2
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_2_R_num_registers,
                    (const unsigned int*) MEMMODEL_2_R_addrs,
                    (const char* const*) MEMMODEL_2_R_names,
                    MEMMODEL_2_W_num_registers,
                    (const unsigned int*) MEMMODEL_2_W_addrs,
                    (const char* const*) MEMMODEL_2_W_names),
                argc, argv, "memory_stats2.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_2_target_addr_bits;
#endif

#ifdef MEMMODEL_3
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_3_R_num_registers,
                    (const unsigned int*) MEMMODEL_3_R_addrs,
                    (const char* const*) MEMMODEL_3_R_names,
                    MEMMODEL_3_W_num_registers,
                    (const unsigned int*) MEMMODEL_3_W_addrs,
                    (const char* const*) MEMMODEL_3_W_names),
                argc, argv, "memory_stats3.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_3_target_addr_bits;
#endif

#ifdef MEMMODEL_4
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_4_R_num_registers,
                    (const unsigned int*) MEMMODEL_4_R_addrs,
                    (const char* const*) MEMMODEL_4_R_names,
                    MEMMODEL_4_W_num_registers,
                    (const unsigned int*) MEMMODEL_4_W_addrs,
                    (const char* const*) MEMMODEL_4_W_names),
                argc, argv, "memory_stats4.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_4_target_addr_bits;
#endif

#ifdef MEMMODEL_5
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_5_R_num_registers,
                    (const unsigned int*) MEMMODEL_5_R_addrs,
                    (const char* const*) MEMMODEL_5_R_names,
                    MEMMODEL_5_W_num_registers,
                    (const unsigned int*) MEMMODEL_5_W_addrs,
                    (const char* const*) MEMMODEL_5_W_names),
                argc, argv, "memory_stats5.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_5_target_addr_bits;
#endif

#ifdef MEMMODEL_6
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_6_R_num_registers,
                    (const unsigned int*) MEMMODEL_6_R_addrs,
                    (const char* const*) MEMMODEL_6_R_names,
                    MEMMODEL_6_W_num_registers,
                    (const unsigned int*) MEMMODEL_6_W_addrs,
                    (const char* const*) MEMMODEL_6_W_names),
                argc, argv, "memory_stats6.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_6_target_addr_bits;
#endif

#ifdef MEMMODEL_7
    fpga_models.push_back(new FASEDMemoryTimingModel(
                this,
                // Casts are required for now since the emitted type can change...
                AddressMap(MEMMODEL_7_R_num_registers,
                    (const unsigned int*) MEMMODEL_7_R_addrs,
                    (const char* const*) MEMMODEL_7_R_names,
                    MEMMODEL_7_W_num_registers,
                    (const unsigned int*) MEMMODEL_7_W_addrs,
                    (const char* const*) MEMMODEL_7_W_names),
                argc, argv, "memory_stats7.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offsets.push_back(host_mem_offset);
     host_mem_offset += 1ULL << MEMMODEL_7_target_addr_bits;
#endif

#ifdef SERIALWIDGET_struct_guard
    #ifdef SERIALWIDGET_0_PRESENT
    SERIALWIDGET_0_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_0_substruct, 0, host_mem_offsets[0]));
    #endif
    #ifdef SERIALWIDGET_1_PRESENT
    SERIALWIDGET_1_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_1_substruct, 1, host_mem_offsets[1]));
    #endif
    #ifdef SERIALWIDGET_2_PRESENT
    SERIALWIDGET_2_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_2_substruct, 2, host_mem_offsets[2]));
    #endif
    #ifdef SERIALWIDGET_3_PRESENT
    SERIALWIDGET_3_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_3_substruct, 3, host_mem_offsets[3]));
    #endif
    #ifdef SERIALWIDGET_4_PRESENT
    SERIALWIDGET_4_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_4_substruct, 4, host_mem_offsets[4]));
    #endif
    #ifdef SERIALWIDGET_5_PRESENT
    SERIALWIDGET_5_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_5_substruct, 5, host_mem_offsets[5]));
    #endif
    #ifdef SERIALWIDGET_6_PRESENT
    SERIALWIDGET_6_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_6_substruct, 6, host_mem_offsets[6]));
    #endif
    #ifdef SERIALWIDGET_7_PRESENT
    SERIALWIDGET_7_substruct_create;
    add_endpoint(new serial_t(this, args, SERIALWIDGET_7_substruct, 7, host_mem_offsets[7]));
    #endif
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
    #ifdef BLOCKDEVWIDGET_4_PRESENT
    BLOCKDEVWIDGET_4_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_4_num_trackers, BLOCKDEVWIDGET_4_latency_bits, BLOCKDEVWIDGET_4_substruct, 4));
    #endif
    #ifdef BLOCKDEVWIDGET_5_PRESENT
    BLOCKDEVWIDGET_5_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_5_num_trackers, BLOCKDEVWIDGET_5_latency_bits, BLOCKDEVWIDGET_5_substruct, 5));
    #endif
    #ifdef BLOCKDEVWIDGET_6_PRESENT
    BLOCKDEVWIDGET_6_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_6_num_trackers, BLOCKDEVWIDGET_6_latency_bits, BLOCKDEVWIDGET_6_substruct, 6));
    #endif
    #ifdef BLOCKDEVWIDGET_7_PRESENT
    BLOCKDEVWIDGET_7_substruct_create;
    add_endpoint(new blockdev_t(this, args, BLOCKDEVWIDGET_7_num_trackers, BLOCKDEVWIDGET_7_latency_bits, BLOCKDEVWIDGET_7_substruct, 7));
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
    #ifdef SIMPLENICWIDGET_4_PRESENT
    SIMPLENICWIDGET_4_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_4_substruct, 4, SIMPLENICWIDGET_4_DMA_ADDR));
    #endif
    #ifdef SIMPLENICWIDGET_5_PRESENT
    SIMPLENICWIDGET_5_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_5_substruct, 5, SIMPLENICWIDGET_5_DMA_ADDR));
    #endif
    #ifdef SIMPLENICWIDGET_6_PRESENT
    SIMPLENICWIDGET_6_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_6_substruct, 6, SIMPLENICWIDGET_6_DMA_ADDR));
    #endif
    #ifdef SIMPLENICWIDGET_7_PRESENT
    SIMPLENICWIDGET_7_substruct_create;
    add_endpoint(new simplenic_t(this, args, SIMPLENICWIDGET_7_substruct, 7, SIMPLENICWIDGET_7_DMA_ADDR));
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
    #ifdef TRACERVWIDGET_4_PRESENT
    TRACERVWIDGET_4_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_4_substruct, 4, TRACERVWIDGET_4_DMA_ADDR));
    #endif
    #ifdef TRACERVWIDGET_5_PRESENT
    TRACERVWIDGET_5_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_5_substruct, 5, TRACERVWIDGET_5_DMA_ADDR));
    #endif
    #ifdef TRACERVWIDGET_6_PRESENT
    TRACERVWIDGET_6_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_6_substruct, 6, TRACERVWIDGET_6_DMA_ADDR));
    #endif
    #ifdef TRACERVWIDGET_7_PRESENT
    TRACERVWIDGET_7_substruct_create;
    add_endpoint(new tracerv_t(this, args, TRACERVWIDGET_7_substruct, 7, TRACERVWIDGET_7_DMA_ADDR));
    #endif
#endif

// There can only be one instance of assert and print widgets as their IO is
// uniquely generated by a FIRRTL transform
#ifdef ASSERTIONWIDGET_struct_guard
    #ifdef ASSERTIONWIDGET_0_PRESENT
    ASSERTIONWIDGET_0_substruct_create;
    add_endpoint(new synthesized_assertions_t(this, ASSERTIONWIDGET_0_substruct));
    #endif
#endif

#ifdef PRINTWIDGET_struct_guard
    #ifdef PRINTWIDGET_0_PRESENT
    PRINTWIDGET_0_substruct_create;
    print_endpoint = new synthesized_prints_t(this,
                                          args,
                                          PRINTWIDGET_0_substruct,
                                          PRINTWIDGET_0_print_count,
                                          PRINTWIDGET_0_token_bytes,
                                          PRINTWIDGET_0_idle_cycles_mask,
                                          PRINTWIDGET_0_print_offsets,
                                          PRINTWIDGET_0_format_strings,
                                          PRINTWIDGET_0_argument_counts,
                                          PRINTWIDGET_0_argument_widths,
                                          PRINTWIDGET_0_DMA_ADDR);
    add_endpoint(print_endpoint);
    #endif
#endif
    // Add functions you'd like to periodically invoke on a paused simulator here.
    if (profile_interval != -1) {
        register_task([this](){ return this->profile_models();}, 0);
    }
}

bool firesim_top_t::simulation_complete() {
    bool is_complete = false;
    for (auto &e: endpoints) {
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
    for (auto &e: endpoints) {
        if (e->exit_code())
            return e->exit_code();
    }
    return 0;
}


void firesim_top_t::run() {
    for (auto &e: fpga_models) {
        e->init();
    }

    for (auto &e: endpoints) {
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
            for (auto &e: endpoints) e->tick();
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
#ifdef PRINTWIDGET_0_PRESENT
    print_endpoint->finish();
#endif
}

