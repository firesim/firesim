#ifdef ASSERTBRIDGEMODULE_struct_guard

#include "synthesized_assertions.h"
#include <iostream>
#include <fstream>

synthesized_assertions_t::~synthesized_assertions_t() {
    free(this->mmio_addrs);
}

void synthesized_assertions_t::tick() {
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
