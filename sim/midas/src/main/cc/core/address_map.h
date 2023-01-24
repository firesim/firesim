// See LICENSE for license details.

#ifndef __ADDRESS_MAP_H
#define __ADDRESS_MAP_H

#include <map>
#include <string>
#include <vector>

// Maps midas compiler emited arrays to a more useful object, that can be
// used to read and write to a local set of registers by their names
//
// Registers may appear in both R and W lists
class AddressMap {
public:
  AddressMap(unsigned int read_register_count,
             const unsigned int *read_register_addrs,
             const char *const *read_register_names,
             unsigned int write_register_count,
             const unsigned int *write_register_addrs,
             const char *const *write_register_names);

  // Look up register address based on name
  uint32_t r_addr(const std::string &name) const {
    return r_registers.find(name)->second;
  };
  uint32_t w_addr(const std::string &name) const {
    return w_registers.find(name)->second;
  };

  // Check for register presence
  bool r_reg_exists(const std::string &name) const {
    return r_registers.find(name) != r_registers.end();
  };
  bool w_reg_exists(const std::string &name) const {
    return w_registers.find(name) != w_registers.end();
  };

  // Register name -> register addresses
  std::map<std::string, uint32_t> r_registers;
  std::map<std::string, uint32_t> w_registers;
};

#endif // __ADDRESS_MAP_H
