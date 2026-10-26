// See LICENSE for license details.

#include "bridges/fpga_managed_stream.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}

class NoncoherentIO : public FPGAManagedStreamIO {
public:
  std::array<char, 64> host{}, device{};
  uint32_t available = 0;
  std::vector<uint32_t> consumed;
  std::vector<std::pair<size_t, size_t>> synced;

  NoncoherentIO() {
    host.fill(char(0xcc));
    for (size_t i = 0; i < device.size(); ++i)
      device[i] = char(i);
  }
  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t) override {
    return {host.data(), 0x10000};
  }
  uint32_t mmio_read(size_t address) override {
    require(address == 0x10, "unexpected stream read");
    return available;
  }
  void mmio_write(size_t address, uint32_t data) override {
    require(address == 0x14, "unexpected stream write");
    consumed.push_back(data);
  }
  // No override keyword so the identical fixture compiles on the baseline.
  void sync_from_device(char *target, size_t size) {
    const size_t offset = target - host.data();
    require(offset <= host.size() && size <= host.size() - offset,
            "refresh exceeds ring");
    synced.emplace_back(offset, size);
    std::memcpy(target, device.data() + offset, size);
  }
};
} // namespace

int main(int argc, char **argv) {
  try {
    require(argc == 2, "expected scenario");
    const std::string scenario = argv[1];
    NoncoherentIO io;
    FPGAManagedStreams::StreamParameters params(
        "modeled-ring", 64, 0, 4, 0x10, 0x14, 8, 12, 16);
    FPGAManagedStreams::FPGAToCPUDriver driver(
        std::move(params), io.host.data(), 0x10000, io);
    std::array<char, 64> output;
    output.fill(char(0x55));
    if (scenario == "linear") {
      io.available = 48;
      require(driver.pull(output.data(), 24, 0) == 24,
              "pull ignored caller limit");
      require(
          std::equal(output.begin(), output.begin() + 24, io.device.begin()),
          "pull read a stale host mirror");
      require(output[24] == 0x55 && io.consumed == std::vector<uint32_t>{24},
              "pull overwrote or consumed beyond request");
      require(io.synced == std::vector<std::pair<size_t, size_t>>{{0, 24}},
              "linear refresh span differs");
    } else if (scenario == "wrap") {
      io.available = 48;
      require(driver.pull(output.data(), 48, 48) == 48, "initial pull failed");
      io.synced.clear();
      for (size_t i = 0; i < io.device.size(); ++i)
        io.device[i] = char(i + 0x80);
      io.available = 32;
      require(driver.pull(output.data(), 32, 32) == 32, "wrapped pull failed");
      for (size_t i = 0; i < 32; ++i)
        require(output[i] == io.device[(48 + i) % 64],
                "wrapped pull read stale data");
      require(io.synced ==
                  std::vector<std::pair<size_t, size_t>>{{48, 16}, {0, 16}},
              "wrap refresh did not split at ring boundary");
      require(io.consumed == std::vector<uint32_t>{48, 32},
              "wrong wrap credit");
    } else if (scenario == "required") {
      io.available = 8;
      require(driver.pull(output.data(), 32, 16) == 0 && io.synced.empty() &&
                  io.consumed.empty() && output[0] == 0x55,
              "insufficient data changed state");
    } else if (scenario == "zero") {
      io.available = 64;
      require(driver.pull(output.data(), 0, 0) == 0 && io.synced.empty() &&
                  output[0] == 0x55,
              "zero pull refreshed or copied data");
    } else {
      throw std::runtime_error("unknown scenario");
    }
    std::printf("%s: PASS (noncoherent ring model, no hardware)\n",
                scenario.c_str());
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
