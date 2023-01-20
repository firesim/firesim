// See LICENSE for license details.

#include "core/simif.h"

char master_t::KIND;

master_t::master_t(simif_t &simif, const SIMULATIONMASTER_struct &mmio_addrs)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs) {}

bool master_t::is_init_done() { return simif.read(mmio_addrs.INIT_DONE); }

bool master_t::is_done() { return simif.read(mmio_addrs.DONE); }

void master_t::step(size_t n, bool blocking) {
  simif.write(mmio_addrs.STEP, n);

  if (blocking) {
    while (!is_done())
      ;
  }
}
