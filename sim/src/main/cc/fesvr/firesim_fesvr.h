#ifndef __FIRESIM_FESVR_H
#define __FIRESIM_FESVR_H

#include <fesvr/htif.h>
#include <string>
#include <vector>
#include <deque>
#include <stdint.h>
#include "midas_context.h"

#define FESVR_CMD_READ 0
#define FESVR_CMD_WRITE 1

#define FESVR_ADDR_CHUNKS 2
#define FESVR_LEN_CHUNKS 2

// Wraps read/write requests that will be shortcircuited through the LOADMEM widget
struct fesvr_loadmem_t {
    fesvr_loadmem_t(): addr(0), size(0) { }
    fesvr_loadmem_t(size_t addr, size_t size): addr(addr), size(size) { }
    size_t addr;
    size_t size;
};

class firesim_fesvr_t : public htif_t
{
    public:
        firesim_fesvr_t(const std::vector<std::string>& args);
        ~firesim_fesvr_t(){};
        bool busy() { return is_busy; }
        bool data_available();
        void send_word(uint32_t word);
        void tick();
        uint32_t recv_word();

        bool recv_loadmem_write_req(fesvr_loadmem_t& loadmem);
        bool recv_loadmem_read_req(fesvr_loadmem_t& loadmem);
        void recv_loadmem_data(void* buf, size_t len);
        bool has_loadmem_reqs();

    protected:
        void idle();
        void reset();
        void load_program() {
            wait(); // Switch back to commit all pending requests
            is_loadmem = true;
            htif_t::load_program();
            is_loadmem = false;
        }

        void read_chunk(reg_t taddr, size_t nbytes, void* dst);
        void write_chunk(reg_t taddr, size_t nbytes, const void* src);

        size_t chunk_align() { return 4; }
        size_t chunk_max_size() { return 1024; }

        int get_ipi_addrs(reg_t *addrs);

        std::deque<uint32_t> in_data;
        std::deque<uint32_t> out_data;
        std::deque<fesvr_loadmem_t> loadmem_write_reqs;
        std::deque<fesvr_loadmem_t> loadmem_read_reqs;
        std::deque<char> loadmem_write_data;

    private:
        bool is_busy;
        // A flag set only during program load to forward fesvr
        // read/write_chunks to the loadmem unit instead of going over tsi
        bool is_loadmem;
        size_t idle_counts;

        void push_addr(reg_t addr);
        void push_len(size_t len);

        void read(uint32_t* data, size_t len);
        void write(const uint32_t* data, size_t len);
        void load_mem_write(addr_t addr, size_t nbytes, const void* src);
        void load_mem_read(addr_t addr, size_t nbytes);

        midas_context_t host;
        midas_context_t* target;

        void wait();
        static int host_thread(void *fesvr);

};

#endif // __FIRESIM_FESVR_H
