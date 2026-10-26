// See LICENSE for license details.
// Predicted MMIO completion; no hardware access.
#include "bridges/fpga_managed_stream.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <type_traits>

namespace {
void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
class PredictedIO : public FPGAManagedStreamIO {
public:
  unsigned polls = 0, ready_after = 0, requests = 0;
  bool fail_read = false;
  std::array<char, 64> buffer{};
  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t) override {
    return {buffer.data(), 0x10000};
  }
  uint32_t mmio_read(size_t address) override {
    require(address == 32, "unexpected flush read");
    if (fail_read)
      throw std::runtime_error("predicted MMIO failure");
    return ++polls >= ready_after;
  }
  void mmio_write(size_t address, uint32_t value) override {
    require(address == 28 && value == 1, "unexpected flush request");
    ++requests;
  }
};
template <typename Driver>
std::unique_ptr<Driver> make_driver(PredictedIO &io) {
  FPGAManagedStreams::StreamParameters params(
      "PREDICTED-flush", 64, 0, 4, 16, 20, 24, 28, 32);
  if constexpr (std::is_constructible_v<Driver,
                                        decltype(params) &&,
                                        void *,
                                        uint64_t,
                                        FPGAManagedStreamIO &,
                                        std::chrono::milliseconds>) {
    return std::make_unique<Driver>(std::move(params),
                                    io.buffer.data(),
                                    0x10000,
                                    io,
                                    std::chrono::milliseconds(2));
  } else {
    return std::make_unique<Driver>(
        std::move(params), io.buffer.data(), 0x10000, io);
  }
}
} // namespace

int main(int argc, char **argv) {
  try {
    require(argc == 2, "scenario required");
    const std::string scenario = argv[1];
    PredictedIO io;
    auto driver = make_driver<FPGAManagedStreams::FPGAToCPUDriver>(io);
    if (scenario == "slow-progress" || scenario == "immediate") {
      io.ready_after = scenario == "slow-progress" ? 1024 : 1;
      driver->flush();
      require(io.polls == io.ready_after && io.requests == 1,
              "flush did not wait for completion");
    } else if (scenario == "timeout" || scenario == "io-error") {
      io.ready_after = ~0u;
      io.fail_read = scenario == "io-error";
      bool rejected = false;
      const auto start = std::chrono::steady_clock::now();
      try {
        driver->flush();
      } catch (const std::runtime_error &error) {
        rejected = std::string(error.what())
                       .find(io.fail_read ? "predicted MMIO failure"
                                          : "timed out") != std::string::npos;
      }
      require(rejected && io.requests == 1,
              "flush failure was not reported to the caller");
      require(std::chrono::steady_clock::now() - start <
                  std::chrono::seconds(1),
              "flush exceeded its configured deadline");
    } else {
      throw std::runtime_error("unknown scenario");
    }
    std::printf("%s: PASS (predicted MMIO, no hardware)\n", scenario.c_str());
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
