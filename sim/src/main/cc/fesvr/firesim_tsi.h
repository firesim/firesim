#ifndef __FIRESIM_TSI_H
#define __FIRESIM_TSI_H

#include "midas_context.h"
#include "firesim_fesvr.h"
#include "fesvr_proxy.h"

class firesim_tsi_t : public fesvr_proxy_t, public firesim_fesvr_t
{
    public:
        firesim_tsi_t(const std::vector<std::string>& args);
        ~firesim_tsi_t();
        void tick();

        bool data_available();
        void send_word(uint32_t word);
        uint32_t recv_word();

        bool recv_loadmem_write_req(fesvr_loadmem_t& loadmem);
        bool recv_loadmem_read_req(fesvr_loadmem_t& loadmem);
        void recv_loadmem_data(void* buf, size_t len);

        bool has_loadmem_reqs();

        bool busy() { return firesim_fesvr_t::busy(); }
        bool done() { return firesim_fesvr_t::done(); }
        int exit_code() { return firesim_fesvr_t::exit_code(); }

    private:
        midas_context_t host;
        midas_context_t* target;

        void wait();
        static int host_thread(void *tsi);
};

#endif // __FIRESIM_TSI_H
