// See LICENSE for license details.

#ifndef __ADDRESS_MAP_H
#define __ADDRESS_MAP_H

#include <cassert>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

// Maps midas compiler emited arrays to a more useful object, that can be
// used to read and write to a local set of registers by their names
//
// Registers may appear in both R and W lists
class AddressMap {
public:
  AddressMap(const std::vector<std::pair<std::string, uint32_t>> &read,
             const std::vector<std::pair<std::string, uint32_t>> &write);

  // Look up register address based on name
  uint32_t r_addr(const std::string &name) const {
    auto it = r_registers.find(name);
    assert(it != r_registers.end() && "missing register");
    return it->second;
  };
  uint32_t w_addr(const std::string &name) const {
    auto it = w_registers.find(name);
    assert(it != r_registers.end() && "missing register");
    return it->second;
  };

  // Check for register presence
  bool r_reg_exists(const std::string &name) const {
    return r_registers.find(name) != r_registers.end();
  };
  bool w_reg_exists(const std::string &name) const {
    return w_registers.find(name) != w_registers.end();
  };

  // Register name -> register addresses
  const std::map<std::string, uint32_t> r_registers;
  const std::map<std::string, uint32_t> w_registers;
};

#endif // __ADDRESS_MAP_H
