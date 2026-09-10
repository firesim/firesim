// See LICENSE for license details.

#ifndef __LOADMEM_H
#define __LOADMEM_H

#include <cstdint>
#include <string>
#include <vector>

#include <gmp.h>

#include "core/config.h"
#include "core/widget.h"

class simif_t;
class StreamEngine;

struct LOADMEMWIDGET_struct {
  uint64_t W_ADDRESS_H;
  uint64_t W_ADDRESS_L;
  uint64_t W_LENGTH;
  uint64_t ZERO_OUT_DRAM;
  // W_DATA is gone: the write payload arrives over a CPU-managed stream rather
  // than through a control-width MMIO register.
  uint64_t ZERO_FINISHED;
  uint64_t R_ADDRESS_H;
  uint64_t R_ADDRESS_L;
  uint64_t R_DATA;
};

class loadmem_t final : public widget_t {
public:
  /// The identifier for the bridge type.
  static char KIND;

  loadmem_t(simif_t &simif,
            const LOADMEMWIDGET_struct &mmio_addrs,
            unsigned index,
            const std::vector<std::string> &args,
            const AXI4Config &mem_conf,
            unsigned mem_data_chunk,
            unsigned from_host_stream_idx);

  /// Supplies the CPU-managed stream used to carry write payload. Set by
  /// simulation_t::init_dram(), which runs after the stream engine is
  /// initialised. When absent, writes fall back to the MMIO path.
  void set_stream_engine(StreamEngine *engine) { stream_engine = engine; }

  void read_mem(size_t addr, mpz_t &value);
  void write_mem(size_t addr, mpz_t &value);
  void write_mem_chunk(size_t addr, mpz_t &value, size_t bytes);

  /// Writes a contiguous span of DRAM, carrying the payload over the
  /// CPU-managed stream. Falls back to write_mem_chunk() if no stream is
  /// available.
  void write_mem_span(size_t addr, const void *data, size_t bytes);

  // Helper to zero out all DRAM.
  void zero_out_dram();

  // Loads the contents of memory from a file.
  void load_mem_from_file(const std::string &filename);

  unsigned get_mem_data_chunk() const { return mem_data_chunk; }

private:
  const LOADMEMWIDGET_struct mmio_addrs;
  const AXI4Config mem_conf;
  const unsigned mem_data_chunk;
  const unsigned from_host_stream_idx;
  StreamEngine *stream_engine = nullptr;
};

#endif // __LOADMEM_H
