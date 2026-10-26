// See LICENSE for license details.

#pragma once
#include "FireSim-generated.const.h"
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#ifdef V80_DDR_STREAM_METADATA
namespace v80_slash_ddr_stream {
constexpr size_t capacity = 2 * 1024 * 1024;
inline void validate(uint64_t ddr, size_t stream, size_t bytes) {
  if (!bytes)
    return;
  if (ddr < V80_DDR_STREAM_MIN_DDR || ddr % 64 || stream > UINT32_MAX ||
      stream % 64 || bytes % 64 || bytes > capacity ||
      ddr > (32ull << 30) - bytes)
    throw std::invalid_argument("Invalid DDR stream DMA range/token alignment");
}
template <class IO, class Clock = std::chrono::steady_clock>
void transfer(IO &io, uint64_t ddr, size_t stream, size_t bytes, bool to_fpga) {
  if (!bytes)
    return;
  validate(ddr, stream, bytes);
  io.write(V80_DDR_STREAM_DDR_LO, uint32_t(ddr));
  io.write(V80_DDR_STREAM_DDR_HI, uint32_t(ddr >> 32));
  io.write(V80_DDR_STREAM_STREAM, uint32_t(stream));
  io.write(V80_DDR_STREAM_BYTES, uint32_t(bytes));
  io.write(V80_DDR_STREAM_COMMAND, to_fpga ? 1 : 2);
  const auto deadline = Clock::now() + std::chrono::seconds(60);
  while (true) {
    auto status = io.read(V80_DDR_STREAM_STATUS);
    if (status & 4)
      throw std::runtime_error("DDR stream DMA rejected range or AXI error");
    if ((status & 3) == 2)
      return;
    if (Clock::now() > deadline)
      throw std::runtime_error("DDR stream DMA completion timeout");
  }
}
} // namespace v80_slash_ddr_stream
#endif
