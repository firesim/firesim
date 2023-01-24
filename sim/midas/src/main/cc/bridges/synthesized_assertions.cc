#include "synthesized_assertions.h"

#include <fstream>
#include <iostream>

char synthesized_assertions_t::KIND;

synthesized_assertions_t::synthesized_assertions_t(
    simif_t &sim,
    const std::vector<std::string> &args,
    const ASSERTBRIDGEMODULE_struct &mmio_addrs,
    const char *const *msgs)
    : bridge_driver_t(sim, &KIND), mmio_addrs(mmio_addrs), msgs(msgs) {
  for (auto &arg : args) {
    if (arg.find("+disable-asserts") == 0)
      enable = false;
  }
}

synthesized_assertions_t::~synthesized_assertions_t() = default;

void synthesized_assertions_t::init() {
  write(mmio_addrs.enable, this->enable);
}

void synthesized_assertions_t::tick() {
  if (!enable)
    return;

  if (read(mmio_addrs.fire)) {
    // Read assertion information
    assert_cycle = read(mmio_addrs.cycle_low);
    assert_cycle |= ((uint64_t)read(mmio_addrs.cycle_high)) << 32;
    assert_id = read(mmio_addrs.id);
    std::cerr << this->msgs[assert_id];
    std::cerr << " at cycle: " << assert_cycle << std::endl;
    assert_fired = true;
  }
}

void synthesized_assertions_t::resume() {
  assert_fired = false;
  write(mmio_addrs.resume, 1);
}
