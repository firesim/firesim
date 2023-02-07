// See LICENSE for license details.

#include "address_map.h"

static std::map<std::string, uint32_t>
make_map(const std::vector<std::pair<std::string, uint32_t>> &addrs) {
  std::map<std::string, uint32_t> regs;
  for (auto &[name, addr] : addrs) {
    regs.emplace(name, addr);
  }
  return regs;
}

AddressMap::AddressMap(
    const std::vector<std::pair<std::string, uint32_t>> &read,
    const std::vector<std::pair<std::string, uint32_t>> &write)
    : r_registers(make_map(read)), w_registers(make_map(write)) {}
