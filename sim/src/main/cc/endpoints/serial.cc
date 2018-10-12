#include <assert.h>
#include "serial.h"

serial_t::serial_t(simif_t* sim, AddressMap addr_map, firesim_fesvr_t* fesvr, uint32_t step_size):
    endpoint_t(sim, addr_map), fesvr(fesvr), step_size(step_size) { }

void serial_t::init() {
    write("step_size", step_size);
    go();
}

void serial_t::go() {
    write("start", 1);
}

void serial_t::send() {
    while(fesvr->data_available() && read("in_ready")) {
        write("in_bits", fesvr->recv_word());
        write("in_valid", 1);
    }
}

void serial_t::recv() {
    while(read("out_valid")) {
        fesvr->send_word(read("out_bits"));
        write("out_ready", 1);
    }
}

void serial_t::tick() {
    // Collect all the responses from the target
    this->recv();
    // First, check to see step_size tokens have been enqueued
    if (!read("done")) return;
    // Punt to FESVR
    if (!fesvr->data_available()) fesvr->tick();
    // Write all the requests to the target
    this->send();
    // Tell the widget license to start generating tokens
    go();
}
