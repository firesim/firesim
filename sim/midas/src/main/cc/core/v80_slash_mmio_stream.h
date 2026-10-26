// See LICENSE for license details.

#pragma once
#include "FireSim-generated.const.h"
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#ifdef V80_MMIO_STREAM_METADATA
namespace v80_slash_mmio_stream {
inline void validate(size_t address, size_t size) {
  if (address > UINT32_MAX || address % 64 || size % 64)
    throw std::invalid_argument(
        "V80 stream transfer must use aligned complete 512-bit tokens");
}
template <class IO>
size_t push(IO &io, size_t address, const char *data, size_t size) {
  if (!size)
    return 0;
  validate(address, size);
  if (!data || size / 4 > UINT32_MAX)
    throw std::invalid_argument("Stream transfer too large");
  io.write(V80_MMIO_STREAM_ADDRESS, uint32_t(address));
  for (size_t i = 0; i < size; i += 4) {
    uint32_t word;
    std::memcpy(&word, data + i, 4);
    io.write(V80_MMIO_STREAM_TX, word);
  }
  return size;
}
template <class IO>
size_t pull(IO &io, size_t address, char *data, size_t size) {
  if (!size)
    return 0;
  validate(address, size);
  if (!data || size / 4 > UINT32_MAX)
    throw std::invalid_argument("Stream transfer too large");
  io.write(V80_MMIO_STREAM_ADDRESS, uint32_t(address));
  io.write(V80_MMIO_STREAM_READ_WORDS, uint32_t(size / 4));
  for (size_t i = 0; i < size; i += 4) {
    uint32_t word = io.read(V80_MMIO_STREAM_RX);
    std::memcpy(data + i, &word, 4);
  }
  return size;
}
} // namespace v80_slash_mmio_stream
#endif
