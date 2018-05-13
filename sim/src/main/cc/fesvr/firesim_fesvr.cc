#include "firesim_fesvr.h"

#define MSIP_BASE 0x2000000

#define NHARTS_MAX 16

firesim_fesvr_t::firesim_fesvr_t(const std::vector<std::string>& args) : htif_t(args)
{
    is_loadmem = false;
    is_busy = false;
    idle_counts = 10;
    for (auto& arg: args) {
        if (arg.find("+idle-counts=") == 0) {
            idle_counts = atoi(arg.c_str()+13);
        }
    }
}

firesim_fesvr_t::~firesim_fesvr_t(void)
{
}

void firesim_fesvr_t::idle()
{
    is_busy = false;
    for (size_t i = 0 ; i < idle_counts ; i++) wait();
    is_busy = true;
}

void firesim_fesvr_t::reset()
{
    uint32_t one = 1;
    write_chunk(MSIP_BASE, sizeof(uint32_t), &one);
}

void firesim_fesvr_t::push_addr(reg_t addr)
{
    uint32_t data[FESVR_ADDR_CHUNKS];
    for (int i = 0; i < FESVR_ADDR_CHUNKS; i++) {
        data[i] = addr & 0xffffffff;
        addr = addr >> 32;
    }
    write(data, FESVR_ADDR_CHUNKS);
}

void firesim_fesvr_t::push_len(size_t len)
{
    uint32_t data[FESVR_LEN_CHUNKS];
    for (int i = 0; i < FESVR_LEN_CHUNKS; i++) {
        data[i] = len & 0xffffffff;
        len = len >> 32;
    }
    write(data, FESVR_LEN_CHUNKS);
}

void firesim_fesvr_t::read_chunk(reg_t taddr, size_t nbytes, void* dst)
{
    const uint32_t cmd = FESVR_CMD_READ;
    uint32_t *result = static_cast<uint32_t*>(dst);
    size_t len = nbytes / sizeof(uint32_t);

    write(&cmd, 1);
    push_addr(taddr);
    push_len(len - 1);

    read(result, len);
}

void firesim_fesvr_t::write_chunk(reg_t taddr, size_t nbytes, const void* src)
{
    const uint32_t cmd = FESVR_CMD_WRITE;
    const uint32_t *src_data = static_cast<const uint32_t*>(src);
    size_t len = nbytes / sizeof(uint32_t);

    // #ifndef __CYGWIN__
    if (is_loadmem) {
        load_mem(taddr, nbytes, src);
    } else
        // #endif
    {
        write(&cmd, 1);
        push_addr(taddr);
        push_len(len - 1);

        write(src_data, len);
    }
}

void firesim_fesvr_t::read(uint32_t* data, size_t len) {
    for (size_t i = 0 ; i < len ; i++) {
        while (out_data.empty()) wait();
        data[i] = out_data.front();
        out_data.pop_front();
    }
}

void firesim_fesvr_t::write(const uint32_t* data, size_t len) {
    in_data.insert(in_data.end(), data, data + len);
}

void firesim_fesvr_t::load_mem(addr_t addr, size_t nbytes, const void* src) {
    loadmem_reqs.push_back(fesvr_loadmem_t(addr, nbytes));
    loadmem_data.insert(loadmem_data.end(), (const char*)src, (const char*)src + nbytes);
}
