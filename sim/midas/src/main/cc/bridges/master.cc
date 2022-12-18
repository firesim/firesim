// See LICENSE for license details.

#include "simif.h"

master_t::master_t(simif_t *sim, const SIMULATIONMASTER_struct &mmio_addrs)
    : sim(sim), mmio_addrs(mmio_addrs) {}

bool master_t::is_init_done() { return sim->read(mmio_addrs.INIT_DONE); }

bool master_t::is_done() { return sim->read(mmio_addrs.DONE); }

void master_t::step(size_t n, bool blocking) {
  sim->write(mmio_addrs.STEP, n);

  if (blocking) {
    while (!is_done())
      ;
  }
}
