// See LICENSE for license details.

#include "master.h"
#include "core/simif.h"
#include <stdexcept>
#include <stdio.h>
#include <string>

char master_t::KIND;

master_t::master_t(simif_t &simif,
                   const SIMULATIONMASTER_struct &mmio_addrs,
                   unsigned index,
                   const std::vector<std::string> &args)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs) {
  assert(index == 0 && "only one simulation master is allowed");
}

bool master_t::is_init_done() {
  const uint32_t value = simif.read(mmio_addrs.INIT_DONE);
  if (value > 1)
    throw std::runtime_error("Invalid simulation master INIT_DONE value: " +
                             std::to_string(value));
  return value == 1;
}

bool master_t::check_fingerprint() {
  uint32_t presence = simif.read(mmio_addrs.PRESENCE_READ);
  printf("FireSim fingerprint: 0x%x\n", presence);
  return presence != 0x46697265;
}

void master_t::write_fingerprint(uint32_t data) {
  simif.write(mmio_addrs.PRESENCE_WRITE, data);
}
