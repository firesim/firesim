#include <assert.h>
#include "serial.h"

serial_t::serial_t(simif_t* sim, firesim_fesvr_t* fesvr, uint32_t step_size):
    endpoint_t(sim), sim(sim), fesvr(fesvr), step_size(step_size) { }

void serial_t::init() {
    write(SERIALWIDGET_0(step_size), step_size);
    go();
}

void serial_t::go() {
    write(SERIALWIDGET_0(start), 1);
}

void serial_t::send() {
    while(fesvr->data_available() && read(SERIALWIDGET_0(in_ready))) {
        write(SERIALWIDGET_0(in_bits), fesvr->recv_word());
        write(SERIALWIDGET_0(in_valid), 1);
    }
}

void serial_t::recv() {
    while(read(SERIALWIDGET_0(out_valid))) {
        fesvr->send_word(read(SERIALWIDGET_0(out_bits)));
        write(SERIALWIDGET_0(out_ready), 1);
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

void serial_t::handle_loadmem_write(fesvr_loadmem_t loadmem) {
    assert(loadmem.size <= 1024);
    static char buf[1024];
    fesvr->recv_loadmem_data(buf, loadmem.size);
    mpz_t data;
    mpz_init(data);
    mpz_import(data, (loadmem.size + sizeof(uint32_t) - 1)/sizeof(uint32_t), -1, sizeof(uint32_t), 0, 0, buf); \
    sim->write_mem_chunk(loadmem.addr, data, loadmem.size);
    mpz_clear(data);
}

void serial_t::serial_bypass_via_loadmem() {
    fesvr_loadmem_t loadmem;
    while (fesvr->has_loadmem_reqs()) {
        // Check for reads first as they preceed a narrow write;
        if (fesvr->recv_loadmem_read_req(loadmem)) handle_loadmem_read(loadmem);
        if (fesvr->recv_loadmem_write_req(loadmem)) handle_loadmem_write(loadmem);
    }
}

void serial_t::tick() {
    // First, check to see step_size tokens have been enqueued
    if (!read(SERIALWIDGET_0(done))) return;
    // Collect all the responses from the target
    this->recv();
    // Punt to FESVR
    if (!fesvr->data_available()) {
        fesvr->tick();
    }
    if (fesvr->has_loadmem_reqs()) {
        serial_bypass_via_loadmem();
    }
    // Write all the requests to the target
    this->send();
    go();
}
