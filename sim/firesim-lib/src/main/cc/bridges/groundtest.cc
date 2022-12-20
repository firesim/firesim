// See LICENSE for license details

#include "groundtest.h"

groundtest_t::groundtest_t(simif_t *sim,
                           const std::vector<std::string> &args,
                           const GROUNDTESTBRIDGEMODULE_struct &mmio_addrs)
    : bridge_driver_t(sim), sim(sim), mmio_addrs(mmio_addrs) {}

groundtest_t::~groundtest_t() {}

void groundtest_t::init() {}

void groundtest_t::tick() { _success = read(this->mmio_addrs.success); }
