#include "firesim_top.h"

// FireSim-defined endpoints
#include "endpoints/serial.h"
#include "endpoints/uart.h"
#include "endpoints/simplenic.h"
#include "endpoints/blockdev.h"
// MIDAS-defined endpoints
#include "endpoints/fpga_model.h"
#include "endpoints/sim_mem.h"
#include "endpoints/fpga_memory_model.h"

firesim_top_t::firesim_top_t(int argc, char** argv, std::vector<firesim_fesvr_t*> fesvr_vec, uint32_t fesvr_step_size): 
    fesvr_vec(fesvr_vec), fesvr_step_size(fesvr_step_size)
{
    // fields to populate to pass to endpoints
    char * blkfile[4] = {NULL};
    uint64_t mac_little_end[4] = {0}; // default to invalid mac addr, force user to specify one
    char * niclogfile = NULL;
    char * shmemportname[4] = {NULL};
    int netbw = MAX_BANDWIDTH, netburst = 8;
    int linklatency = 0;
    char subslotid_str[2] = {NULL};



    std::vector<std::string> args(argv + 1, argv + argc);
    max_cycles = -1;
    char subslotid[4] = {NULL};
    char * slotid;
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

        if (arg.find("+blkdev") == 0) {
           subslotid_str[0] = arg.at(7);
           int subnode_id = atoi(subslotid_str); //extract the subnode id
           blkfile[subnode_id] = const_cast<char*>(arg.c_str()) + 9;
        }
        if (arg.find("+niclog=") == 0) {
            niclogfile = const_cast<char*>(arg.c_str()) + 8;
        }
        if (arg.find("+slotid=") == 0) {
            slotid = const_cast<char*>(arg.c_str()) + 8;
        }

        if (arg.find("+shmemportname") == 0) {
            subslotid_str[0] = arg.at(14);
            int subnode_id = atoi(subslotid_str); //extract the subnode id
            shmemportname[subnode_id] = const_cast<char*>(arg.c_str()) + 16;
        }

        if (arg.find("+zero-out-dram") == 0) {
            do_zero_out_dram = true;
        }
        if (arg.find("+macaddr") == 0) {
            uint8_t mac_bytes[6];
            int mac_octets[6];
            char * macstring = NULL;
            subslotid_str[0] = arg.at(8);
            int subnode_id = atoi(subslotid_str);
            macstring = const_cast<char*>(arg.c_str()) + 10;
            char * trailingjunk;

            // convert mac address from string to 48 bit int
            if (6 == sscanf(macstring, "%x:%x:%x:%x:%x:%x%c",
                        &mac_octets[0], &mac_octets[1], &mac_octets[2],
                        &mac_octets[3], &mac_octets[4], &mac_octets[5],
                        trailingjunk)) {

                for (int i = 0; i < 6; i++) {
                    mac_little_end[subnode_id] |= (((uint64_t)(uint8_t)mac_octets[i]) << (8*i));
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
    }

    subslotid[0] = '0';
    subslotid[1] = '1';
    subslotid[2] = '2';
    subslotid[3] = '3';

    add_endpoint(new uart_t(this, AddressMap(             UARTWIDGET_0_R_num_registers,
                                  (const unsigned int*) UARTWIDGET_0_R_addrs,
                                  (const char* const*)  UARTWIDGET_0_R_names,
                                                        UARTWIDGET_0_W_num_registers,
                                  (const unsigned int*) UARTWIDGET_0_W_addrs,
                                  (const char* const*)  UARTWIDGET_0_W_names), 
               slotid, subslotid[0]));
     add_endpoint(new uart_t(this, AddressMap(             UARTWIDGET_1_R_num_registers,
                                  (const unsigned int*) UARTWIDGET_1_R_addrs,
                                  (const char* const*)  UARTWIDGET_1_R_names,
                                                        UARTWIDGET_1_W_num_registers,
                                  (const unsigned int*) UARTWIDGET_1_W_addrs,
                                  (const char* const*)  UARTWIDGET_1_W_names),
               slotid, subslotid[1]));
    add_endpoint(new uart_t(this, AddressMap(             UARTWIDGET_2_R_num_registers,
                                  (const unsigned int*) UARTWIDGET_2_R_addrs,
                                  (const char* const*)  UARTWIDGET_2_R_names,
                                                        UARTWIDGET_2_W_num_registers,
                                  (const unsigned int*) UARTWIDGET_2_W_addrs,
                                  (const char* const*)  UARTWIDGET_2_W_names), 
               slotid, subslotid[2]));
    add_endpoint(new uart_t(this, AddressMap(             UARTWIDGET_3_R_num_registers,
                                  (const unsigned int*) UARTWIDGET_3_R_addrs,
                                  (const char* const*)  UARTWIDGET_3_R_names,
                                                        UARTWIDGET_3_W_num_registers,
                                  (const unsigned int*) UARTWIDGET_3_W_addrs,
                                  (const char* const*)  UARTWIDGET_3_W_names), 
               slotid, subslotid[3]));

    add_endpoint(new serial_t(this, AddressMap(           SERIALWIDGET_0_R_num_registers,
                                  (const unsigned int*) SERIALWIDGET_0_R_addrs,
                                  (const char* const*)  SERIALWIDGET_0_R_names,
                                                        SERIALWIDGET_0_W_num_registers,
                                  (const unsigned int*) SERIALWIDGET_0_W_addrs,
                                  (const char* const*)  SERIALWIDGET_0_W_names), 
                 fesvr_vec[0], fesvr_step_size));
    add_endpoint(new serial_t(this, AddressMap(           SERIALWIDGET_1_R_num_registers,
                                  (const unsigned int*) SERIALWIDGET_1_R_addrs,
                                  (const char* const*)  SERIALWIDGET_1_R_names,
                                                        SERIALWIDGET_1_W_num_registers,
                                  (const unsigned int*) SERIALWIDGET_1_W_addrs,
                                  (const char* const*)  SERIALWIDGET_1_W_names), 
                 fesvr_vec[1], fesvr_step_size));
    add_endpoint(new serial_t(this, AddressMap(           SERIALWIDGET_2_R_num_registers,
                                  (const unsigned int*) SERIALWIDGET_2_R_addrs,
                                  (const char* const*)  SERIALWIDGET_2_R_names,
                                                        SERIALWIDGET_2_W_num_registers,
                                  (const unsigned int*) SERIALWIDGET_2_W_addrs,
                                  (const char* const*)  SERIALWIDGET_2_W_names), 
                 fesvr_vec[2], fesvr_step_size));
    add_endpoint(new serial_t(this, AddressMap(           SERIALWIDGET_3_R_num_registers,
                                  (const unsigned int*) SERIALWIDGET_3_R_addrs,
                                  (const char* const*)  SERIALWIDGET_3_R_names,
                                                        SERIALWIDGET_3_W_num_registers,
                                  (const unsigned int*) SERIALWIDGET_3_W_addrs,
                                  (const char* const*)  SERIALWIDGET_3_W_names), 
                 fesvr_vec[3], fesvr_step_size));


    add_endpoint(new simplenic_t(this, AddressMap(  SIMPLENICWIDGET_0_R_num_registers,
                              (const unsigned int*) SIMPLENICWIDGET_0_R_addrs,
                              (const char* const*)  SIMPLENICWIDGET_0_R_names,
                                                    SIMPLENICWIDGET_0_W_num_registers,
                              (const unsigned int*) SIMPLENICWIDGET_0_W_addrs,
                              (const char* const*)  SIMPLENICWIDGET_0_W_names),
                 slotid, subslotid, mac_little_end, netbw, netburst, linklatency, shmemportname));
   
    add_endpoint(new blockdev_t(this, AddressMap(   BLOCKDEVWIDGET_0_R_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_0_R_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_0_R_names,
                                                    BLOCKDEVWIDGET_0_W_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_0_W_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_0_W_names),
                                blkfile[0]));
    add_endpoint(new blockdev_t(this, AddressMap(   BLOCKDEVWIDGET_1_R_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_1_R_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_1_R_names,
                                                    BLOCKDEVWIDGET_1_W_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_1_W_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_1_W_names),
                              blkfile[1]));
    add_endpoint(new blockdev_t(this, AddressMap(   BLOCKDEVWIDGET_2_R_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_2_R_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_2_R_names,
                                                    BLOCKDEVWIDGET_2_W_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_2_W_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_2_W_names),
                              blkfile[2]));
    add_endpoint(new blockdev_t(this, AddressMap(   BLOCKDEVWIDGET_3_R_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_3_R_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_3_R_names,
                                                    BLOCKDEVWIDGET_3_W_num_registers,
                              (const unsigned int*) BLOCKDEVWIDGET_3_W_addrs,
                              (const char* const*)  BLOCKDEVWIDGET_3_W_names),
                              blkfile[3]));


                // add more endpoints here

/*
#ifdef NASTIWIDGET_0
    endpoints.push_back(new sim_mem_t(this, argc, argv));
#endif
*/

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
                argc, argv, "memory_stats0.csv"));
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
                argc, argv, "memory_stats1.csv"));
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
                argc, argv, "memory_stats2.csv"));
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
                argc, argv, "memory_stats3.csv"));
#endif

    //add loadmem "endpoints"
    loadmem_vec.push_back(loadmem_m(this, AddressMap(           LOADMEM_0_R_num_registers,
                                 (const unsigned int*) LOADMEM_0_R_addrs,
                                 (const char* const*)  LOADMEM_0_R_names,
                                                       LOADMEM_0_W_num_registers,
                                 (const unsigned int*) LOADMEM_0_W_addrs,
                                 (const char* const*)  LOADMEM_0_W_names)));

    loadmem_vec.push_back(loadmem_m(this, AddressMap(           LOADMEM_1_R_num_registers,
                                 (const unsigned int*) LOADMEM_1_R_addrs,
                                 (const char* const*)  LOADMEM_1_R_names,
                                                       LOADMEM_1_W_num_registers,
                                 (const unsigned int*) LOADMEM_1_W_addrs,
                                 (const char* const*)  LOADMEM_1_W_names)));

    loadmem_vec.push_back(loadmem_m(this, AddressMap(           LOADMEM_2_R_num_registers,
                                 (const unsigned int*) LOADMEM_2_R_addrs,
                                 (const char* const*)  LOADMEM_2_R_names,
                                                       LOADMEM_2_W_num_registers,
                                 (const unsigned int*) LOADMEM_2_W_addrs,
                                 (const char* const*)  LOADMEM_2_W_names)));

    loadmem_vec.push_back(loadmem_m(this, AddressMap(           LOADMEM_3_R_num_registers,
                                 (const unsigned int*) LOADMEM_3_R_addrs,
                                 (const char* const*)  LOADMEM_3_R_names,
                                                       LOADMEM_3_W_num_registers,
                                 (const unsigned int*) LOADMEM_3_W_addrs,
                                 (const char* const*)  LOADMEM_3_W_names)));

}

void firesim_top_t::handle_loadmem_read(fesvr_loadmem_t loadmem, firesim_fesvr_t* fesvr, loadmem_m ldmem) {
    assert(loadmem.size % sizeof(uint32_t) == 0);
    // Loadmem reads are in granularities of the width of the FPGA-DRAM bus
    mpz_t buf;
    mpz_init(buf);
    while (loadmem.size > 0) {
        ldmem.read_mem(loadmem.addr, buf);

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

void firesim_top_t::handle_loadmem_write(fesvr_loadmem_t loadmem, firesim_fesvr_t* fesvr, loadmem_m ldmem) {
    assert(loadmem.size <= 1024);
    static char buf[1024];
    fesvr->recv_loadmem_data(buf, loadmem.size);
    mpz_t data;
    mpz_init(data);
    mpz_import(data, (loadmem.size + sizeof(uint32_t) - 1)/sizeof(uint32_t), -1, sizeof(uint32_t), 0, 0, buf); \
    ldmem.write_mem(loadmem.addr, data, loadmem.size);
    mpz_clear(data);
}

void firesim_top_t::serial_bypass_via_loadmem(firesim_fesvr_t* fesvr, loadmem_m ldmem) {
    fesvr_loadmem_t loadmem;
    while (fesvr->has_loadmem_reqs()) {
        // Check for reads first as they preceed a narrow write;
        if (fesvr->recv_loadmem_read_req(loadmem)) handle_loadmem_read(loadmem, fesvr, ldmem);
        if (fesvr->recv_loadmem_write_req(loadmem)) handle_loadmem_write(loadmem, fesvr, ldmem);
    }
}

void firesim_top_t::loop(size_t step_size, uint64_t coarse_step_size) {
    size_t loop_start = cycles();
    size_t loop_end = cycles() + coarse_step_size;
    unsigned int fesvr_done_counter=0;

    do {
      fesvr_done_counter=0;
        step(step_size, false);

        while(!done()){
            for (auto e: endpoints) e->tick();
        }
        for (int i=0; i<fesvr_vec.size(); i++) {
          firesim_fesvr_t* fesvr = fesvr_vec[i];
          loadmem_m ldmem = loadmem_vec[i];
          if (fesvr->has_loadmem_reqs() && !fesvr->data_available()) {
              serial_bypass_via_loadmem(fesvr);
          }
        }

        if (fesvr->done()) {
           fesvr_done_counter++;
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
        for (auto ldmem: loadmem_vec) {
           ldmem.zero_out_dram();
        }
    }
    fprintf(stderr, "Commencing simulation.\n");

    // Assert reset T=0 -> 50
    target_reset(0, 50);

    start_time = timestamp();

    unsigned int fesvr_done_counter=0;
    do {
        fesvr_done_counter=0;
        // Every profile_interval iterations, collect state from all fpga models
        for (auto fesvr: fesvr_vec) {
            for (auto mod: fpga_models) {
                mod->profile();
            }
            loop(fesvr_step_size, profile_interval);
            if (fesvr->done()) {
                fesvr_done_counter++;
                break;
            }
        }
        loop(fesvr_step_size, profile_interval);
    } while ((fesvr_done_counter == 0) && cycles() <= max_cycles);

    uint64_t end_time = timestamp();
    double sim_time = diff_secs(end_time, start_time);
    double sim_speed = ((double) cycles()) / (sim_time * 1000.0);
    if (sim_speed > 1000.0) {
        fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f MHz\n", sim_time, sim_speed / 1000.0);
    } else {
        fprintf(stderr, "time elapsed: %.1f s, simulation speed = %.2f KHz\n", sim_time, sim_speed);
    }

    for (auto fesvr: fesvr_vec) { 
      int exitcode = fesvr->exit_code();
      if (exitcode) {
        fprintf(stderr, "*** FAILED *** (code = %d) after %llu cycles\n", exitcode, cycles());
      } else if (cycles() > max_cycles) {
        fprintf(stderr, "*** FAILED *** (timeout) after %llu cycles\n", cycles());
      } else {
        fprintf(stderr, "*** PASSED *** after %llu cycles\n", cycles());
      }
      expect(!exitcode, NULL);
    }
    for (auto e: fpga_models) {
        e->finish();
    }
} 
