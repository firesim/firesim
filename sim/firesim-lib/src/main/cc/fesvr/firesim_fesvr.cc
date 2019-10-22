//See LICENSE for license details
#include "firesim_fesvr.h"

#define MSIP_BASE 0x2000000

#define NHARTS_MAX 16

int firesim_fesvr_t::host_thread(void *arg)
{
    firesim_fesvr_t *fesvr = static_cast<firesim_fesvr_t*>(arg);
    fesvr->run();

    while (true)
        fesvr->target->switch_to();

    return 0;
}

firesim_fesvr_t::firesim_fesvr_t(const std::vector<std::string> args) : htif_t(args)
{
    is_loadmem = false;
    is_busy = false;
    idle_counts = 10;
    for (auto& arg: args) {
        if (arg.find("+idle-counts=") == 0) {
            idle_counts = atoi(arg.c_str()+13);
        }
    }

    target = midas_context_t::current();
    host.init(host_thread, this);
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

    // If we are in htif::load_program route all reads through the loadmem unit
    if (is_loadmem) {
        load_mem_read(taddr, nbytes);
    } else {
        write(&cmd, 1);
        push_addr(taddr);
        push_len(len - 1);
    }
    read(result, len);
}

void firesim_fesvr_t::write_chunk(reg_t taddr, size_t nbytes, const void* src)
{
    const uint32_t cmd = FESVR_CMD_WRITE;
    const uint32_t *src_data = static_cast<const uint32_t*>(src);
    size_t len = nbytes / sizeof(uint32_t);

    // If we are in htif::load_program route all writes through the loadmem unit
    if (is_loadmem) {
        load_mem_write(taddr, nbytes, src);
    } else {
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

void firesim_fesvr_t::load_mem_write(addr_t addr, size_t nbytes, const void* src) {
    loadmem_write_reqs.push_back(fesvr_loadmem_t(addr, nbytes));
    loadmem_write_data.insert(loadmem_write_data.end(), (const char*)src, (const char*)src + nbytes);
}

void firesim_fesvr_t::load_mem_read(addr_t addr, size_t nbytes) {
    loadmem_read_reqs.push_back(fesvr_loadmem_t(addr, nbytes));
}

void firesim_fesvr_t::tick()
{
    host.switch_to();
}

void firesim_fesvr_t::wait()
{
    target->switch_to();
}

void firesim_fesvr_t::send_word(uint32_t word)
{
    out_data.push_back(word);
}

uint32_t firesim_fesvr_t::recv_word()
{
    uint32_t word = in_data.front();
    in_data.pop_front();
    return word;
}

bool firesim_fesvr_t::data_available()
{
    return !in_data.empty();
}

bool firesim_fesvr_t::has_loadmem_reqs() {
    return(!loadmem_write_reqs.empty() || !loadmem_read_reqs.empty());
}

bool firesim_fesvr_t::recv_loadmem_write_req(fesvr_loadmem_t& loadmem) {
    if (loadmem_write_reqs.empty()) return false;
    auto r = loadmem_write_reqs.front();
    loadmem.addr = r.addr;
    loadmem.size = r.size;
    loadmem_write_reqs.pop_front();
    return true;
}

bool firesim_fesvr_t::recv_loadmem_read_req(fesvr_loadmem_t& loadmem) {
    if (loadmem_read_reqs.empty()) return false;
    auto r = loadmem_read_reqs.front();
    loadmem.addr = r.addr;
    loadmem.size = r.size;
    loadmem_read_reqs.pop_front();
    return true;
}

void firesim_fesvr_t::recv_loadmem_data(void* buf, size_t len) {
    std::copy(loadmem_write_data.begin(), loadmem_write_data.begin() + len, (char*)buf);
    loadmem_write_data.erase(loadmem_write_data.begin(), loadmem_write_data.begin() + len);
}
