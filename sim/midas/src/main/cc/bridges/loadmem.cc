// See LICENSE for license details.

#include "loadmem.h"

#include <algorithm>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <vector>

#include "core/simif.h"

char loadmem_t::KIND;

loadmem_t::loadmem_t(simif_t &simif,
                     const LOADMEMWIDGET_struct &mmio_addrs,
                     unsigned index,
                     const std::vector<std::string> &args,
                     const AXI4Config &mem_conf,
                     unsigned mem_data_chunk)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs), mem_conf(mem_conf),
      mem_data_chunk(mem_data_chunk) {
  assert(index == 0 && "only one loadmem widget is allowed");
  if (mem_data_chunk == 0)
    throw std::invalid_argument("loadmem beat must contain at least one word");
}

void loadmem_t::load_mem_from_file(const std::string &filename) {
  fprintf(stdout, "[loadmem] start loading file: %s\n", filename.c_str());
  std::ifstream file(filename.c_str());
  if (!file) {
    fprintf(stderr, "[loadmem] cannot open %s\n", filename.c_str());
    exit(EXIT_FAILURE);
  }

  const size_t chunk = mem_conf.data_bits / 4;
  fprintf(stdout, "[loadmem] start loading, chunk = %ld\n", chunk);

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
  simif.flush_dram_writes();
  fprintf(stdout, "[loadmem] done\n");
}

void loadmem_t::read_mem(size_t addr, mpz_t &value) {
  simif.flush_dram_writes();
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
  write_words(addr, value, 1);
}

void loadmem_t::write_mem_chunk(size_t addr, mpz_t &value, size_t bytes) {
  if (bytes == 0)
    return;
  const size_t beat_bytes = size_t(mem_data_chunk) * sizeof(uint32_t);
  const size_t num_beats = bytes / beat_bytes + (bytes % beat_bytes != 0);
  write_words(addr, value, num_beats);
}

void loadmem_t::write_words(size_t addr, mpz_t &value, size_t num_beats) {
  if (num_beats >
      std::numeric_limits<size_t>::max() / sizeof(uint32_t) / mem_data_chunk)
    throw std::length_error("loadmem write length overflow");
  const size_t words = num_beats * mem_data_chunk;
  const size_t bits = mpz_sizeinbase(value, 2);
  const size_t source_words = bits / 32 + (bits % 32 != 0);
  // Export into owned storage, including room for a value wider than the
  // requested span. Only the low requested words are written, as with MMIO.
  std::vector<uint32_t> data(std::max(words, source_words), 0);
  size_t exported;
  mpz_export(data.data(), &exported, -1, sizeof(uint32_t), 0, 0, value);
  if (simif.write_dram_bulk(addr, data.data(), words * sizeof(uint32_t)))
    return;
  // A backend may accept only some writes. Do not let this MMIO fallback
  // overtake an earlier accepted write that is still buffered.
  simif.flush_dram_writes();
  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  simif.write(mmio_addrs.W_LENGTH, num_beats);
  for (size_t i = 0; i < words; i++)
    simif.write(mmio_addrs.W_DATA, data[i]);
}

void loadmem_t::zero_out_dram() {
  simif.flush_dram_writes();
  simif.write(mmio_addrs.ZERO_OUT_DRAM, 1);
  while (!simif.read(mmio_addrs.ZERO_FINISHED))
    ;
}
