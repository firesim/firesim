
#ifndef __SIMPLENIC_H
#define __SIMPLENIC_H

#include "endpoints/endpoint.h"
#include <vector>

// TODO this should not be hardcoded here.
#define MAX_BANDWIDTH 200

#ifdef SIMPLENICWIDGET_struct_guard
class simplenic_t: public endpoint_t
{
    public:
        simplenic_t(simif_t* sim, std::vector<std::string> &args,
            SIMPLENICWIDGET_struct *addrs, int simplenicno,
            long dma_addr);
        ~simplenic_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; };
        virtual int exit_code() { return 0; }

    private:
        simif_t* sim;
        uint64_t mac_lendian;
        char * pcis_read_bufs[2];
        char * pcis_write_bufs[2];
        int rlimit_inc, rlimit_period, rlimit_size;

        // link latency in cycles
        // assuming 3.2 GHz, this number / 3.2 = link latency in ns
        // e.g. setting this to 6405 gives you 6405/3.2 = 2001.5625 ns latency
        // IMPORTANT: this must be a multiple of 7
        int LINKLATENCY;
        FILE * niclog;
        SIMPLENICWIDGET_struct *mmio_addrs;
        bool loopback;

        // checking for token loss
        uint32_t next_token_from_fpga = 0x0;
        uint32_t next_token_from_socket = 0x0;

        uint64_t iter = 0;

        int currentround = 0;

        // only for TOKENVERIFY
        uint64_t timeelapsed_cycles = 0;

        long dma_addr;
};
#endif // SIMPLENICWIDGET_struct_guard

#endif // __SIMPLENIC_H
