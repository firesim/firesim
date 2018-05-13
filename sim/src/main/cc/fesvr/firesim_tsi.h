#ifndef __FIRESIM_TSI_H
#define __FIRESIM_TSI_H

#include "midas_context.h"
#include "firesim_fesvr.h"
#include "fesvr_proxy.h"

class firesim_tsi_t : public fesvr_proxy_t, public firesim_fesvr_t
{
    public:
        firesim_tsi_t(const std::vector<std::string>& args);
        virtual ~firesim_tsi_t();
        virtual void tick();

        virtual bool data_available();
        virtual void send_word(uint32_t word);
        virtual uint32_t recv_word();

        virtual bool recv_loadmem_req(fesvr_loadmem_t& loadmem);
        virtual void recv_loadmem_data(void* buf, size_t len);

        virtual bool busy() {
            return firesim_fesvr_t::busy();
        }
        virtual bool done() {
            return firesim_fesvr_t::done();
        }
        virtual int exit_code() {
            return firesim_fesvr_t::exit_code();
        }

    private:
        midas_context_t host;
        midas_context_t* target;

        virtual void wait();
        static int host_thread(void *tsi);
};

#endif // __FIRESIM_TSI_H
