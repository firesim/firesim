#ifndef __FIRESIM_FESVR_H
#define __FIRESIM_FESVR_H

#include <fesvr/htif.h>
#include <string>
#include <vector>
#include <deque>
#include <stdint.h>
#include "fesvr_proxy.h"

#define FESVR_CMD_READ 0
#define FESVR_CMD_WRITE 1

#define FESVR_ADDR_CHUNKS 2
#define FESVR_LEN_CHUNKS 2

class firesim_fesvr_t : public htif_t
{
    public:
        firesim_fesvr_t(const std::vector<std::string>& args);
        virtual ~firesim_fesvr_t();
        virtual void wait() = 0;
        bool busy() { return is_busy; }

    protected:
        virtual void idle();
        virtual void reset();
        virtual void load_program() {
            is_loadmem = true;
            htif_t::load_program();
            is_loadmem = false;
        }

        virtual void read_chunk(reg_t taddr, size_t nbytes, void* dst);
        virtual void write_chunk(reg_t taddr, size_t nbytes, const void* src);

        size_t chunk_align() { return 4; }
        size_t chunk_max_size() { return 1024; }

        int get_ipi_addrs(reg_t *addrs);

        std::deque<uint32_t> in_data;
        std::deque<uint32_t> out_data;
        std::deque<fesvr_loadmem_t> loadmem_reqs;
        std::deque<char> loadmem_data;

    private:
        bool is_busy;
        bool is_loadmem;
        size_t idle_counts;

        void push_addr(reg_t addr);
        void push_len(size_t len);

        virtual void read(uint32_t* data, size_t len);
        virtual void write(const uint32_t* data, size_t len);
        virtual void load_mem(addr_t addr, size_t nbytes, const void* src);
};

#endif // __FIRESIM_FESVR_H
