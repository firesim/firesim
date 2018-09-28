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

firesim_top_t::firesim_top_t(int argc, char** argv, firesim_fesvr_t* fesvr, uint32_t fesvr_step_size): 
    fesvr(fesvr), fesvr_step_size(fesvr_step_size)
{
    // fields to populate to pass to endpoints
    char * blkfile = NULL;
    char * niclogfile = NULL;
    char * slotid = NULL;
    char * tracefile = NULL;
    uint64_t mac_little_end = 0; // default to invalid mac addr, force user to specify one
    int netbw = MAX_BANDWIDTH, netburst = 8;
    int linklatency = 0;

    std::vector<std::string> args(argv + 1, argv + argc);
    max_cycles = -1;
    for (auto &arg: args) {
        if (arg.find("+max-cycles=") == 0) {
            max_cycles = atoi(arg.c_str()+12);
        }

        if (arg.find("+profile-interval=") == 0) {
            profile_interval = atoi(arg.c_str()+18);
        } else {
            profile_interval = max_cycles;
        }

        if (arg.find("+fesvr-step-size=") == 0) {
            fesvr_step_size = atoi(arg.c_str()+17);
        }
        if (arg.find("+blkdev=") == 0) {
            blkfile = const_cast<char*>(arg.c_str()) + 8;
        }
        if (arg.find("+niclog=") == 0) {
            niclogfile = const_cast<char*>(arg.c_str()) + 8;
        }
        if (arg.find("+slotid=") == 0) {
            slotid = const_cast<char*>(arg.c_str()) + 8;
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
    }

    add_endpoint(new uart_t(this));
    add_endpoint(new serial_t(this, fesvr, fesvr_step_size));

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

    add_endpoint(new blockdev_t(this, blkfile));
    add_endpoint(new simplenic_t(this, slotid, mac_little_end, netbw, netburst, linklatency, niclogfile));
    add_endpoint(new tracerv_t(this, tracefile));
    // add more endpoints here

}

void firesim_top_t::handle_loadmem_read(fesvr_loadmem_t loadmem) {
    assert(loadmem.size % sizeof(uint32_t) == 0);
    // Loadmem reads are in granularities of the width of the FPGA-DRAM bus
    mpz_t buf;
    mpz_init(buf);
    while (loadmem.size > 0) {
        read_mem(loadmem.addr, buf);

        // If the read word is 0; mpz_export seems to return an array with length 0
        size_t beats_requested = (loadmem.size/sizeof(uint32_t) > MEM_DATA_CHUNK) ?
                                 MEM_DATA_CHUNK :
                                 loadmem.size/sizeof(uint32_t);
        // The number of beats exported from buf; may be less than beats requested.
        size_t non_zero_beats;
        uint32_t* data = (uint32_t*)mpz_export(NULL, &non_zero_beats, -1, sizeof(uint32_t), 0, 0, buf);
        for (size_t j = 0; j < beats_requested; j++) {
            if (j < non_zero_beats) {
                fesvr->send_word(data[j]);
            } else {
                fesvr->send_word(0);
            }
        }
        loadmem.size -= beats_requested * sizeof(uint32_t);
    }
    mpz_clear(buf);
    // Switch back to fesvr for it to process read data
    fesvr->tick();
}

void firesim_top_t::handle_loadmem_write(fesvr_loadmem_t loadmem) {
    assert(loadmem.size <= 1024);
    static char buf[1024];
    fesvr->recv_loadmem_data(buf, loadmem.size);
    mpz_t data;
    mpz_init(data);
    mpz_import(data, (loadmem.size + sizeof(uint32_t) - 1)/sizeof(uint32_t), -1, sizeof(uint32_t), 0, 0, buf); \
    write_mem_chunk(loadmem.addr, data, loadmem.size);
    mpz_clear(data);
}

void firesim_top_t::serial_bypass_via_loadmem() {
    fesvr_loadmem_t loadmem;
    while (fesvr->has_loadmem_reqs()) {
        // Check for reads first as they preceed a narrow write;
        if (fesvr->recv_loadmem_read_req(loadmem)) handle_loadmem_read(loadmem);
        if (fesvr->recv_loadmem_write_req(loadmem)) handle_loadmem_write(loadmem);
    }
}

void firesim_top_t::loop(size_t step_size, uint64_t coarse_step_size) {
    size_t loop_start = cycles();
    size_t loop_end = cycles() + coarse_step_size;

    do {
        step(step_size, false);

        while(!done()){
            for (auto e: endpoints) e->tick();
        }
        if (fesvr->has_loadmem_reqs() && !fesvr->data_available()) {
            serial_bypass_via_loadmem();
        }
    } while (!fesvr->done() && cycles() < loop_end && cycles() <= max_cycles);
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

    // Assert reset T=0 -> 50
    target_reset(0, 50);

    uint64_t start_time = timestamp();

    do {
        // Every profile_interval iterations, collect state from all fpga models
        for (auto mod: fpga_models) {
            mod->profile();
        }
        loop(fesvr_step_size, profile_interval);
    } while (!fesvr->done() && cycles() <= max_cycles);


    uint64_t end_time = timestamp();
    double sim_time = diff_secs(end_time, start_time);
    double sim_speed = ((double) cycles()) / (sim_time * 1000.0);

    // always print a newline after target's output
    fprintf(stderr, "\n");
    if (sim_speed > 1000.0) {
        fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f MHz\n", sim_time, sim_speed / 1000.0);
    } else {
        fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f KHz\n", sim_time, sim_speed);
    }
    int exitcode = fesvr->exit_code();
    if (exitcode) {
        fprintf(stderr, "*** FAILED *** (code = %d) after %llu cycles\n", exitcode, cycles());
    } else if (cycles() > max_cycles) {
        fprintf(stderr, "*** FAILED *** (timeout) after %llu cycles\n", cycles());
    } else {
        fprintf(stderr, "*** PASSED *** after %llu cycles\n", cycles());
    }
    expect(!exitcode, NULL);

    for (auto e: fpga_models) {
        e->finish();
    }
} 
