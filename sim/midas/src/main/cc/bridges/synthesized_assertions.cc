#ifdef ASSERTBRIDGEMODULE_struct_guard

#include "synthesized_assertions.h"
#include <fstream>
#include <iostream>

synthesized_assertions_t::synthesized_assertions_t(
    simif_t *sim,
    std::vector<std::string> &args,
    ASSERTBRIDGEMODULE_struct *mmio_addrs,
    const char *const *msgs)
    : bridge_driver_t(sim), mmio_addrs(mmio_addrs), msgs(msgs) {
  for (auto &arg : args) {
    if (arg.find("+disable-asserts") == 0)
      enable = false;
  }
}

synthesized_assertions_t::~synthesized_assertions_t() {
  free(this->mmio_addrs);
}

void synthesized_assertions_t::init() {
  write(this->mmio_addrs->enable, this->enable);
}

void synthesized_assertions_t::tick() {
  if (!enable)
    return;

  if (read(this->mmio_addrs->fire)) {
    // Read assertion information
    assert_cycle = read(this->mmio_addrs->cycle_low);
    assert_cycle |= ((uint64_t)read(this->mmio_addrs->cycle_high)) << 32;
    assert_id = read(this->mmio_addrs->id);
    std::cerr << this->msgs[assert_id];
    std::cerr << " at cycle: " << assert_cycle << std::endl;
    assert_fired = true;
  }
}

void synthesized_assertions_t::resume() {
  assert_fired = false;
  write(this->mmio_addrs->resume, 1);
}

#endif // ASSERTBRIDGEMODULE_struct_guard
