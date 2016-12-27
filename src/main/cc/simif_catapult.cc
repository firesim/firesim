#include "simif_catapult.h"
#include <cassert>

simif_catapult_t::simif_catapult_t() {
  catapult_start();
}

simif_catapult_t::~simif_catapult_t() {
  catapult_finish();
}

void simif_catapult_t::write(size_t addr, uint64_t data) {
  catapult_softreg_write(addr, data);
}

uint64_t simif_catapult_t::read(size_t addr) {
  return catapult_softreg_read(addr);
}
