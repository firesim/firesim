#include "address_map.h"

AddressMap::AddressMap(
    unsigned int r_register_count,
    const unsigned int* r_register_addrs,
    const char* const* r_register_names,
    unsigned int w_register_count,
    const unsigned int* w_register_addrs,
    const char* const* w_register_names) {

  for (size_t i = 0; i < r_register_count; i++) {
    r_registers.insert(std::make_pair(r_register_names[i], r_register_addrs[i]));
  }

  for (size_t i = 0; i < w_register_count; i++) {
    w_registers.insert(std::make_pair(w_register_names[i], w_register_addrs[i]));
  }
}
