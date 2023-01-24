// See LICENSE for license details.

#include "loadmem.h"

#include <fstream>

#include "core/simif.h"

char loadmem_t::KIND;

loadmem_t::loadmem_t(simif_t &simif,
                     const LOADMEMWIDGET_struct &mmio_addrs,
                     const AXI4Config &mem_conf,
                     unsigned mem_data_chunk)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs), mem_conf(mem_conf),
      mem_data_chunk(mem_data_chunk) {}

void loadmem_t::load_mem_from_file(const std::string &filename) {
  fprintf(stdout, "[loadmem] start loading\n");
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }
  const size_t chunk = mem_conf.data_bits / 4;
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
  simif.write(mmio_addrs.R_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.R_ADDRESS_L, addr & ((1ULL << 32) - 1));
  uint32_t data[mem_data_chunk];
  for (size_t i = 0; i < mem_data_chunk; i++) {
    data[i] = simif.read(mmio_addrs.R_DATA);
  }
  mpz_import(value, mem_data_chunk, -1, sizeof(uint32_t), 0, 0, data);
}

void loadmem_t::write_mem(size_t addr, mpz_t &value) {
  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  simif.write(mmio_addrs.W_LENGTH, 1);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(nullptr, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < mem_data_chunk; i++) {
    simif.write(mmio_addrs.W_DATA, i < size ? data[i] : 0);
  }
}

static size_t ceil_div(size_t a, size_t b) { return ((a)-1) / (b) + 1; }

void loadmem_t::write_mem_chunk(size_t addr, mpz_t &value, size_t bytes) {
  const unsigned mem_data_chunk_bytes = mem_data_chunk * sizeof(uint32_t);

  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));

  size_t num_beats = ceil_div(bytes, mem_data_chunk_bytes);
  simif.write(mmio_addrs.W_LENGTH, num_beats);
  size_t size;
  uint32_t *data =
      (uint32_t *)mpz_export(nullptr, &size, -1, sizeof(uint32_t), 0, 0, value);
  for (size_t i = 0; i < num_beats * mem_data_chunk; i++) {
    simif.write(mmio_addrs.W_DATA, i < size ? data[i] : 0);
  }
}

void loadmem_t::zero_out_dram() {
  simif.write(mmio_addrs.ZERO_OUT_DRAM, 1);
  while (!simif.read(mmio_addrs.ZERO_FINISHED))
    ;
}
