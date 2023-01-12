// See LICENSE for license details.

#include "clock.h"

#include "core/simif.h"

clockmodule_t::clockmodule_t(simif_t *sim,
                             const CLOCKBRIDGEMODULE_struct &mmio_addrs)
    : sim(sim), mmio_addrs(mmio_addrs) {}

uint64_t clockmodule_t::tcycle() {
  sim->write(mmio_addrs.tCycle_latch, 1);
  uint32_t cycle_l = sim->read(mmio_addrs.tCycle_0);
  uint32_t cycle_h = sim->read(mmio_addrs.tCycle_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}

uint64_t clockmodule_t::hcycle() {
  sim->write(mmio_addrs.hCycle_latch, 1);
  uint32_t cycle_l = sim->read(mmio_addrs.hCycle_0);
  uint32_t cycle_h = sim->read(mmio_addrs.hCycle_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}
