// See LICENSE for license details.

#include "core/simif.h"

char master_t::KIND;

master_t::master_t(simif_t &simif, const SIMULATIONMASTER_struct &mmio_addrs)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs) {}

bool master_t::is_init_done() { return simif.read(mmio_addrs.INIT_DONE); }
