// See LICENSE for license details.

#include "loadmem.h"

#include <fstream>
#include <vector>

#include "core/simif.h"
#include "core/stream_engine.h"

char loadmem_t::KIND;

loadmem_t::loadmem_t(simif_t &simif,
                     const LOADMEMWIDGET_struct &mmio_addrs,
                     unsigned index,
                     const std::vector<std::string> &args,
                     const AXI4Config &mem_conf,
                     unsigned mem_data_chunk,
                     unsigned from_host_stream_idx)
    : widget_t(simif, &KIND), mmio_addrs(mmio_addrs), mem_conf(mem_conf),
      mem_data_chunk(mem_data_chunk),
      from_host_stream_idx(from_host_stream_idx) {
  assert(index == 0 && "only one loadmem widget is allowed");
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

static size_t ceil_div(size_t a, size_t b) { return ((a)-1) / (b) + 1; }

// mpz-based entry points, kept for existing callers. Both marshal into bytes and
// defer to write_mem_span, which is now the only way payload reaches the widget.
void loadmem_t::write_mem(size_t addr, mpz_t &value) {
  const unsigned beat_bytes = mem_data_chunk * sizeof(uint32_t);
  std::vector<char> buf(beat_bytes, 0);
  size_t exported = 0;
  mpz_export(buf.data(), &exported, -1, sizeof(char), 0, 0, value);
  write_mem_span(addr, buf.data(), beat_bytes);
}

void loadmem_t::write_mem_chunk(size_t addr, mpz_t &value, size_t bytes) {
  const unsigned beat_bytes = mem_data_chunk * sizeof(uint32_t);
  const size_t padded = ceil_div(bytes, beat_bytes) * beat_bytes;
  std::vector<char> buf(padded, 0);
  size_t exported = 0;
  mpz_export(buf.data(), &exported, -1, sizeof(char), 0, 0, value);
  write_mem_span(addr, buf.data(), padded);
}

// Writes a span of DRAM with the payload carried over a CPU-managed stream.
//
// The widget's LoadMemWriter already issues AXI bursts, and zero_out_dram()
// shows it can fill DRAM with no host data crossing the bus at all. What was
// slow is the payload: W_DATA was a control-width (32-bit) MMIO register, so a
// 4 KiB chunk cost 1027 MMIO writes -- one per four bytes of DRAM. Here the
// address and length still go over MMIO (three writes), and the payload goes
// over the stream, which is 512 bits wide and DMA-backed.
void loadmem_t::write_mem_span(size_t addr, const void *data, size_t bytes) {
  // There is no MMIO fallback: W_DATA was removed from the widget, so a target
  // without a CPU-managed stream cannot have its DRAM initialised at all.
  if (stream_engine == nullptr) {
    fprintf(stderr,
            "[loadmem] no CPU-managed stream available; cannot write DRAM\n");
    abort();
  }

  const unsigned beat_bytes = mem_data_chunk * sizeof(uint32_t);
  simif.write(mmio_addrs.W_ADDRESS_H, addr >> 32);
  simif.write(mmio_addrs.W_ADDRESS_L, addr & ((1ULL << 32) - 1));
  simif.write(mmio_addrs.W_LENGTH, ceil_div(bytes, beat_bytes));

  // push() is permitted to accept less than requested, so drain the buffer.
  const char *src = static_cast<const char *>(data);
  size_t remaining = bytes;
  while (remaining > 0) {
    size_t pushed = stream_engine->push(
        from_host_stream_idx, const_cast<char *>(src), remaining, 0);
    if (pushed == 0) {
      stream_engine->push_flush(from_host_stream_idx);
      continue;
    }
    src += pushed;
    remaining -= pushed;
  }
  stream_engine->push_flush(from_host_stream_idx);
}

void loadmem_t::zero_out_dram() {
  simif.write(mmio_addrs.ZERO_OUT_DRAM, 1);
  while (!simif.read(mmio_addrs.ZERO_FINISHED))
    ;
}
