#include "synthesized_assertions.h"

#include <fstream>
#include <iostream>
#include <sstream>

char synthesized_assertions_t::KIND;

synthesized_assertions_t::synthesized_assertions_t(
    simif_t &sim,
    const ASSERTBRIDGEMODULE_struct &mmio_addrs,
    unsigned index,
    const std::vector<std::string> &args,
    std::vector<const char *> &&msgs,
    std::vector<context_t> &&contexts)
    : bridge_driver_t(sim, &KIND), mmio_addrs(mmio_addrs),
      msgs(std::move(msgs)), contexts(std::move(contexts)) {
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
    if (!contexts.empty())
      std::cerr << "Assertion context: " << format_contexts() << std::endl;
    assert_fired = true;
  }
}

std::string synthesized_assertions_t::exit_summary() {
  return format_contexts();
}

std::string synthesized_assertions_t::format_contexts() {
  std::ostringstream out;
  for (size_t i = 0; i < contexts.size(); i++) {
    uint64_t value = read(contexts[i].addr_low);
    value |= ((uint64_t)read(contexts[i].addr_high)) << 32;
    out << (i ? ", " : "") << contexts[i].label << "=" << value;
  }
  return out.str();
}

void synthesized_assertions_t::resume() {
  assert_fired = false;
  write(mmio_addrs.resume, 1);
}
