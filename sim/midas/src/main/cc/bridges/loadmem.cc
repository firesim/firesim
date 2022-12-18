// See LICENSE for license details.

#include "loadmem.h"

#include <fstream>

#include "simif.h"

loadmem_t::loadmem_t(simif_t *sim,
                     const LOADMEMWIDGET_struct &mmio_addrs,
                     unsigned mem_data_chunk)
    : sim(sim), mmio_addrs(mmio_addrs), mem_data_chunk(mem_data_chunk) {}

void loadmem_t::load_mem_from_file(const std::string &filename) {
  fprintf(stdout, "[loadmem] start loading\n");
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  const size_t chunk = MEM_DATA_BITS / 4;
  size_t addr = 0;
  std::string line;
  mpz_t data;
  mpz_init(data);
  while (std::getline(file, line)) {
    assert(line.length() % chunk == 0);
    for (int j = line.length() - chunk; j >= 0; j -= chunk) {
      mpz_set_str(data, line.substr(j, chunk).c_str(), 16);
      write_mem(addr, data);
      addr += chunk / 2;
    }
  }
  mpz_clear(data);
  file.close();
  fprintf(stdout, "[loadmem] done\n");
}

void loadmem_t::read_mem(size_t addr, mpz_t &value) {
  // NB: mpz_t variables may not export <size> <uint32_t> beats, if initialized
  // with an array of zeros.
  sim->write(mmio_addrs.R_ADDRESS_H, addr >> 32);
  sim->write(mmio_addrs.R_ADDRESS_L, addr & ((1ULL << 32) - 1));
  const size_t size = MEM_DATA_CHUNK;
  uint32_t data[size];
  for (size_t i = 0; i < size; i++) {
    data[i] = sim->read(mmio_addrs.R_DATA);
  }
  mpz_import(value, size, -1, sizeof(uint32_t), 0, 0, data);
}

void loadmem_t::write_mem(size_t addr, mpz_t &value) {
  sim->write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  sim->write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  sim->write(mmio_addrs.W_LENGTH, 1);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(NULL, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < MEM_DATA_CHUNK; i++) {
    sim->write(mmio_addrs.W_DATA, i < size ? data[i] : 0);
  }
}

static size_t ceil_div(size_t a, size_t b) { return ((a)-1) / (b) + 1; }

void loadmem_t::write_mem_chunk(size_t addr, mpz_t &value, size_t bytes) {
  const unsigned mem_data_chunk_bytes = mem_data_chunk * sizeof(uint32_t);

  sim->write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  sim->write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));

  size_t num_beats = ceil_div(bytes, mem_data_chunk_bytes);
  sim->write(mmio_addrs.W_LENGTH, num_beats);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(NULL, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < num_beats * MEM_DATA_CHUNK; i++) {
    sim->write(mmio_addrs.W_DATA, i < size ? data[i] : 0);
  }
}

void loadmem_t::zero_out_dram() {
  sim->write(mmio_addrs.ZERO_OUT_DRAM, 1);
  while (!sim->read(mmio_addrs.ZERO_FINISHED))
    ;
}
