#include <assert.h>
#include "serial.h"

serial_t::serial_t(simif_t* sim, firesim_fesvr_t* fesvr, uint32_t step_size):
    endpoint_t(sim), fesvr(fesvr), step_size(step_size) { }

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

void serial_t::tick() {
    // Collect all the responses from the target
    this->recv();
    // First, check to see step_size tokens have been enqueued
    if (!read(SERIALWIDGET_0(done))) return;
    // Punt to FESVR
    if (!fesvr->data_available()) fesvr->tick();
    // Write all the requests to the target
    this->send();
    // Tell the widget license to start generating tokens
    go();
}
