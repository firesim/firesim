#ifdef ASSERTBRIDGEMODULE_struct_guard

#include "synthesized_assertions.h"
#include <iostream>
#include <fstream>


synthesized_assertions_t::synthesized_assertions_t(simif_t* sim,
        ASSERTBRIDGEMODULE_struct * mmio_addrs): bridge_driver_t(sim) {
    this->mmio_addrs = mmio_addrs;
};

synthesized_assertions_t::~synthesized_assertions_t() {
    free(this->mmio_addrs);
}

void synthesized_assertions_t::tick() {
  if (read(this->mmio_addrs->fire)) {
    // Read assertion information
    std::vector<std::string> msgs;
    std::ifstream file(std::string(TARGET_NAME) + ".asserts");
    std::string line;
    std::ostringstream oss;
    while (std::getline(file, line)) {
      if (line == "0") {
        msgs.push_back(oss.str());
        oss.str(std::string());
      } else {
        oss << line << std::endl;
      }
    }
    assert_cycle = read(this->mmio_addrs->cycle_low);
    assert_cycle |= ((uint64_t)read(this->mmio_addrs->cycle_high)) << 32;
    assert_id = read(this->mmio_addrs->id);
    std::cerr << msgs[assert_id];
    std::cerr << " at cycle: " << assert_cycle << std::endl;
    assert_fired = true;
  }
}

void synthesized_assertions_t::resume() {
  assert_fired = false;
  write(this->mmio_addrs->resume, 1);
}

#endif // ASSERTBRIDGEMODULE_struct_guard
