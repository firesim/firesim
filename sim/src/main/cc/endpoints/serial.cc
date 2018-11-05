#ifdef SERIALWIDGET_struct_guard

#include <assert.h>
#include "serial.h"

#if defined(SIMULATION_XSIM) || defined(RTLSIM)
#define DEFAULT_STEPSIZE (128)
#else
#define DEFAULT_STEPSIZE (2004765L)
#endif

serial_t::serial_t(simif_t* sim, const std::vector<std::string>& args, SERIALWIDGET_struct * mmio_addrs):
        endpoint_t(sim), sim(sim), fesvr(args) {

    this->mmio_addrs = mmio_addrs;

    step_size = DEFAULT_STEPSIZE;
    for (auto &arg: args) {
        if (arg.find("+fesvr-step-size=") == 0) {
            step_size = atoi(arg.c_str()+17);
        }
    }
}

serial_t::~serial_t() {
    free(this->mmio_addrs);
}

void serial_t::init() {
    write(this->mmio_addrs->step_size, step_size);
    go();
}

void serial_t::go() {
    write(this->mmio_addrs->start, 1);
}

void serial_t::send() {
    while(fesvr.data_available() && read(this->mmio_addrs->in_ready)) {
        write(this->mmio_addrs->in_bits, fesvr.recv_word());
        write(this->mmio_addrs->in_valid, 1);
    }
}

void serial_t::recv() {
    while(read(this->mmio_addrs->out_valid)) {
        fesvr.send_word(read(this->mmio_addrs->out_bits));
        write(this->mmio_addrs->out_ready, 1);
    }
}

void serial_t::handle_loadmem_read(fesvr_loadmem_t loadmem) {
    assert(loadmem.size % sizeof(uint32_t) == 0);
    // Loadmem reads are in granularities of the width of the FPGA-DRAM bus
    mpz_t buf;
    mpz_init(buf);
    while (loadmem.size > 0) {
        sim->read_mem(loadmem.addr, buf);

        // If the read word is 0; mpz_export seems to return an array with length 0
        size_t beats_requested = (loadmem.size/sizeof(uint32_t) > MEM_DATA_CHUNK) ?
                                 MEM_DATA_CHUNK :
                                 loadmem.size/sizeof(uint32_t);
        // The number of beats exported from buf; may be less than beats requested.
        size_t non_zero_beats;
        uint32_t* data = (uint32_t*)mpz_export(NULL, &non_zero_beats, -1, sizeof(uint32_t), 0, 0, buf);
        for (size_t j = 0; j < beats_requested; j++) {
            if (j < non_zero_beats) {
                fesvr.send_word(data[j]);
            } else {
                fesvr.send_word(0);
            }
        }
        loadmem.size -= beats_requested * sizeof(uint32_t);
    }
    mpz_clear(buf);
    // Switch back to fesvr for it to process read data
    fesvr.tick();
}

void serial_t::handle_loadmem_write(fesvr_loadmem_t loadmem) {
    assert(loadmem.size <= 1024);
    static char buf[1024];
    fesvr.recv_loadmem_data(buf, loadmem.size);
    mpz_t data;
    mpz_init(data);
    mpz_import(data, (loadmem.size + sizeof(uint32_t) - 1)/sizeof(uint32_t), -1, sizeof(uint32_t), 0, 0, buf); \
    sim->write_mem_chunk(loadmem.addr, data, loadmem.size);
    mpz_clear(data);
}

void serial_t::serial_bypass_via_loadmem() {
    fesvr_loadmem_t loadmem;
    while (fesvr.has_loadmem_reqs()) {
        // Check for reads first as they preceed a narrow write;
        if (fesvr.recv_loadmem_read_req(loadmem)) handle_loadmem_read(loadmem);
        if (fesvr.recv_loadmem_write_req(loadmem)) handle_loadmem_write(loadmem);
    }
}

void serial_t::tick() {
    // First, check to see step_size tokens have been enqueued
    if (!read(this->mmio_addrs->done)) return;
    // Collect all the responses from the target
    this->recv();
    // Punt to FESVR
    if (!fesvr.data_available()) {
        fesvr.tick();
    }
    if (fesvr.has_loadmem_reqs()) {
        serial_bypass_via_loadmem();
    }
    if (!terminate()) {
        // Write all the requests to the target
        this->send();
        go();
    }
}

#endif // SERIALWIDGET_struct_guard
