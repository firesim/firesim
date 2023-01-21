// See LICENSE for license details.

#include "address_map.h"

AddressMap::AddressMap(unsigned int read_register_count,
                       const unsigned int *read_register_addrs,
                       const char *const *read_register_names,
                       unsigned int write_register_count,
                       const unsigned int *write_register_addrs,
                       const char *const *write_register_names) {

  for (size_t i = 0; i < read_register_count; i++) {
    r_registers.insert(
        std::make_pair(read_register_names[i], read_register_addrs[i]));
  }

  for (size_t i = 0; i < write_register_count; i++) {
    w_registers.insert(
        std::make_pair(write_register_names[i], write_register_addrs[i]));
  }
}
