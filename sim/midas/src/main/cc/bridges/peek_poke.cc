// See LICENSE for license details.

#include "peek_poke.h"

char peek_poke_t::KIND;

peek_poke_t::peek_poke_t(simif_t &simif,
                         const PEEKPOKEBRIDGEMODULE_struct &mmio_addrs,
                         unsigned poke_size,
                         const uint32_t *input_addrs,
                         const char *const *input_names,
                         const uint32_t *input_chunks,
                         unsigned peek_size,
                         const uint32_t *output_addrs,
                         const char *const *output_names,
                         const uint32_t *output_chunks)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs) {
  for (unsigned i = 0; i < poke_size; ++i) {
    inputs.emplace(std::string(input_names[i]),
                   port{input_addrs[i], input_chunks[i]});
  }
  for (unsigned i = 0; i < peek_size; ++i) {
    outputs.emplace(std::string(output_names[i]),
                    port{output_addrs[i], output_chunks[i]});
  }
}

void peek_poke_t::poke(std::string_view id, uint32_t value, bool blocking) {
  req_timeout = false;
  req_unstable = false;

  auto it = inputs.find(id);
  assert(it != inputs.end() && "missing input port");

  if (blocking && !wait_on_done(10.0)) {
    req_timeout = true;
    return;
  }

  simif.write(it->second.address, value);
}

uint32_t peek_poke_t::peek(std::string_view id, bool blocking) {
  req_timeout = false;
  req_unstable = false;

  auto it = outputs.find(id);
  assert(it != outputs.end() && "missing output port");

  if (blocking && !wait_on_done(10.0)) {
    req_timeout = true;
    return 0;
  }

  req_unstable = blocking && !wait_on_stable_peeks(0.1);
  return simif.read(it->second.address);
}

void peek_poke_t::poke(std::string_view id, mpz_t &value) {
  req_timeout = false;
  req_unstable = false;

  auto it = inputs.find(id);
  assert(it != inputs.end() && "missing input port");

  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(nullptr, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < it->second.chunks; i++) {
    simif.write(it->second.address + (i * sizeof(uint32_t)),
                i < size ? data[i] : 0);
  }
}

void peek_poke_t::peek(std::string_view id, mpz_t &value) {
  req_timeout = false;
  req_unstable = false;

  auto it = outputs.find(id);
  assert(it != outputs.end() && "missing output port");

  const size_t size = it->second.chunks;
  uint32_t data[size];
  for (size_t i = 0; i < size; i++) {
    data[i] = simif.read((size_t)it->second.address + (i * sizeof(uint32_t)));
  }
  mpz_import(value, size, -1, sizeof(uint32_t), 0, 0, data);
}

bool peek_poke_t::is_done() { return simif.read(mmio_addrs.DONE); }

void peek_poke_t::step(size_t n, bool blocking) {
  simif.write(mmio_addrs.STEP, n);

  if (blocking) {
    while (!is_done())
      ;
  }
}
