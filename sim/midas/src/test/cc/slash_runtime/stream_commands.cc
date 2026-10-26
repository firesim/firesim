// See LICENSE for license details.
// Exercise production command helpers with predicted register responses.
#include "core/v80_slash_ddr_stream.h"
#include "core/v80_slash_mmio_stream.h"
#include <array>
#include <limits>
#include <string>
#include <utility>
#include <vector>

void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
template <class Fn>
void rejected(Fn fn) {
  bool threw = false;
  try {
    fn();
  } catch (const std::exception &) {
    threw = true;
  }
  require(threw, "invalid command was accepted");
}
struct Clock {
  using time_point = std::chrono::steady_clock::time_point;
  static inline int seconds = 0;
  static time_point now() {
    return time_point(std::chrono::seconds(seconds++));
  }
};
struct IO {
  std::vector<std::pair<size_t, uint32_t>> writes;
  std::vector<size_t> reads;
  std::vector<uint32_t> responses;
  size_t index = 0;
  uint32_t fallback = 1;
  void write(size_t address, uint32_t value) {
    writes.emplace_back(address, value);
  }
  uint32_t read(size_t address) {
    reads.push_back(address);
    return index < responses.size() ? responses[index++] : fallback;
  }
};
void transfer(IO &io, uint64_t address, size_t stream, size_t size, bool push) {
#ifdef SLASH_HELPER_LEGACY_CLOCK
  v80_slash_ddr_stream::transfer(io, address, stream, size, push);
#else
  v80_slash_ddr_stream::transfer<IO, Clock>(io, address, stream, size, push);
#endif
}
int main(int argc, char **argv) {
  try {
    require(argc == 2, "one case required");
    std::string name(argv[1]);
    IO io;
    std::array<char, 128> bytes;
    for (size_t i = 0; i < bytes.size(); ++i)
      bytes[i] = char(i);
    if (name == "ddr-push" || name == "ddr-pull") {
      // Address high bits and the largest staging transfer must survive.
      constexpr uint64_t address = (5ull << 30) + 64;
      io.responses = {1, 0, 2};
      const bool push = name == "ddr-push";
      transfer(io, address, 128, v80_slash_ddr_stream::capacity, push);
      require(io.writes ==
                  std::vector<std::pair<size_t, uint32_t>>{
                      {V80_DDR_STREAM_DDR_LO, uint32_t(address)},
                      {V80_DDR_STREAM_DDR_HI, uint32_t(address >> 32)},
                      {V80_DDR_STREAM_STREAM, 128},
                      {V80_DDR_STREAM_BYTES, v80_slash_ddr_stream::capacity},
                      {V80_DDR_STREAM_COMMAND, push ? 1u : 2u}},
              "DMA command differs");
      require(io.reads == std::vector<size_t>(3, V80_DDR_STREAM_STATUS),
              "DMA status reads differ");
    } else if (name == "ddr-error" || name == "ddr-error-done") {
      io.responses = {name == "ddr-error" ? 4u : 6u};
      rejected([&] { transfer(io, V80_DDR_STREAM_MIN_DDR, 0, 64, true); });
      require(io.reads.size() == 1, "DMA error did not stop polling");
    } else if (name == "ddr-timeout") {
      rejected([&] { transfer(io, V80_DDR_STREAM_MIN_DDR, 0, 64, true); });
      require(io.reads.size() == 61, "DMA deadline was not enforced");
    } else if (name == "ddr-zero") {
      transfer(io, 1, 3, 0, true);
      require(io.writes.empty() && io.reads.empty(), "empty DMA had effects");
    } else if (name.rfind("ddr-invalid-", 0) == 0) {
      uint64_t address = V80_DDR_STREAM_MIN_DDR;
      size_t stream = 0, size = 64;
      if (name == "ddr-invalid-low")
        address -= 64;
      else if (name == "ddr-invalid-alignment")
        address += 1;
      else if (name == "ddr-invalid-stream")
        stream = uint64_t(UINT32_MAX) + 1;
      else if (name == "ddr-invalid-token")
        size = 65;
      else if (name == "ddr-invalid-capacity")
        size = v80_slash_ddr_stream::capacity + 64;
      else if (name == "ddr-invalid-wrap")
        address = UINT64_MAX - 63;
      else
        throw std::runtime_error("unknown invalid case");
      io.fallback = 2;
      rejected([&] { transfer(io, address, stream, size, true); });
      require(io.writes.empty() && io.reads.empty(),
              "invalid DMA reached registers");
    } else if (name == "mmio-push" || name == "mmio-pull") {
      const auto expected = bytes;
      const bool push = name == "mmio-push";
      for (size_t i = 0; i < bytes.size(); i += 4) {
        uint32_t word;
        std::memcpy(&word, bytes.data() + i, 4);
        io.responses.push_back(word);
      }
      if (push) {
        require(v80_slash_mmio_stream::push(
                    io, 128, bytes.data(), bytes.size()) == bytes.size(),
                "push count");
        require(io.writes.size() == 33 && io.reads.empty(),
                "push register count");
        for (size_t i = 0; i < 32; ++i)
          require(io.writes[i + 1] == std::make_pair(size_t(V80_MMIO_STREAM_TX),
                                                     io.responses[i]),
                  "push word order");
      } else {
        bytes.fill(0);
        require(v80_slash_mmio_stream::pull(
                    io, 128, bytes.data(), bytes.size()) == bytes.size(),
                "pull count");
        require(bytes == expected &&
                    io.reads == std::vector<size_t>(32, V80_MMIO_STREAM_RX),
                "pull words/order");
        require(io.writes.size() == 2 &&
                    io.writes[1] ==
                        std::make_pair(size_t(V80_MMIO_STREAM_READ_WORDS), 32u),
                "pull count command");
      }
      require(io.writes[0] ==
                  std::make_pair(size_t(V80_MMIO_STREAM_ADDRESS), 128u),
              "stream address");
    } else if (name == "mmio-zero") {
      require(!v80_slash_mmio_stream::push(io, 3, nullptr, 0),
              "empty push count");
      require(!v80_slash_mmio_stream::pull(io, 3, nullptr, 0),
              "empty pull count");
      require(io.writes.empty() && io.reads.empty(), "empty MMIO had effects");
    } else if (name == "mmio-invalid") {
      rejected([&] { v80_slash_mmio_stream::push(io, 3, bytes.data(), 64); });
      rejected([&] { v80_slash_mmio_stream::pull(io, 0, bytes.data(), 63); });
      rejected([&] {
        v80_slash_mmio_stream::push(
            io, uint64_t(UINT32_MAX) + 1, bytes.data(), 64);
      });
      rejected([&] {
        v80_slash_mmio_stream::pull(
            io, 0, bytes.data(), (uint64_t(UINT32_MAX) + 1) * 4);
      });
      require(io.writes.empty() && io.reads.empty(),
              "invalid MMIO reached registers");
    } else if (name == "mmio-null") {
      rejected([&] { v80_slash_mmio_stream::push(io, 0, nullptr, 64); });
      rejected([&] { v80_slash_mmio_stream::pull(io, 0, nullptr, 64); });
      require(io.writes.empty() && io.reads.empty(),
              "null MMIO reached registers");
    } else
      throw std::runtime_error("unknown case");
    return 0;
  } catch (const std::exception &error) {
    fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
