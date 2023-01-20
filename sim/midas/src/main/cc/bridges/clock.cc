// See LICENSE for license details.

#include "clock.h"

#include "core/simif.h"

char clockmodule_t::KIND;

clockmodule_t::clockmodule_t(simif_t &simif,
                             const CLOCKBRIDGEMODULE_struct &mmio_addrs)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs) {}

uint64_t clockmodule_t::tcycle() {
  simif.write(mmio_addrs.tCycle_latch, 1);
  uint32_t cycle_l = simif.read(mmio_addrs.tCycle_0);
  uint32_t cycle_h = simif.read(mmio_addrs.tCycle_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}

uint64_t clockmodule_t::hcycle() {
  simif.write(mmio_addrs.hCycle_latch, 1);
  uint32_t cycle_l = simif.read(mmio_addrs.hCycle_0);
  uint32_t cycle_h = simif.read(mmio_addrs.hCycle_1);
  return (((uint64_t)cycle_h) << 32) | cycle_l;
}
