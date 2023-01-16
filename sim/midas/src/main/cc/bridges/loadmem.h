// See LICENSE for license details.

#ifndef __LOADMEM_H
#define __LOADMEM_H

#include <cstdint>

#include <gmp.h>

class simif_t;

typedef struct LOADMEMWIDGET_struct {
  uint64_t W_ADDRESS_H;
  uint64_t W_ADDRESS_L;
  uint64_t W_LENGTH;
  uint64_t ZERO_OUT_DRAM;
  uint64_t W_DATA;
  uint64_t ZERO_FINISHED;
  uint64_t R_ADDRESS_H;
  uint64_t R_ADDRESS_L;
  uint64_t R_DATA;
} LOADMEMWIDGET_struct;

class loadmem_t final {
public:
  loadmem_t(simif_t *sim,
            const LOADMEMWIDGET_struct &mmio_addrs,
            const AXI4Config &mem_conf,
            unsigned mem_data_chunk);

  void read_mem(size_t addr, mpz_t &value);
  void write_mem(size_t addr, mpz_t &value);
  void write_mem_chunk(size_t addr, mpz_t &value, size_t bytes);

  // Helper to zero out all DRAM.
  void zero_out_dram();

  // Loads the contents of memory from a file.
  void load_mem_from_file(const std::string &filename);

  unsigned get_mem_data_chunk() const { return mem_data_chunk; }

private:
  simif_t *sim;
  const LOADMEMWIDGET_struct mmio_addrs;
  const AXI4Config mem_conf;
  const unsigned mem_data_chunk;
};

#endif // __LOADMEM_H
