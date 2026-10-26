// See LICENSE for license details.
// Production ring drivers + complete generated platform; DDR and primitives
// PREDICTED.
#define SLASH_FPGA_STREAM_TEST
#include "bridges/fpga_managed_stream.h"
#include "driver-parameters.h"
#include "slash_platform_test.cc"
#include <array>
#include <cstring>
#include <vector>

class PredictedStreamIO : public FPGAManagedStreamIO {
public:
  Simulation &sim;
  std::array<char, 8192> input{}, output{};
  unsigned pushes = 0, pulls = 0;
  explicit PredictedStreamIO(Simulation &sim) : sim(sim) {}
  uint32_t mmio_read(size_t addr) override { return sim.read(addr); }
  void mmio_write(size_t addr, uint32_t v) override { sim.write(addr, v); }
  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t) override {
    throw std::runtime_error("fixture rings already explicitly allocated");
  }
  void sync_to_device(char *host, size_t size) override {
    const auto offset = host - input.data();
    require(offset >= 0 && size <= input.size() - offset,
            "invalid input mirror span");
    for (size_t i = 0; i < size; i += 8) {
      uint64_t value;
      std::memcpy(&value, host + i, 8);
      sim.predicted[memory_base + 0x20000 + offset + i] = value;
    }
    ++pushes;
  }
  void sync_from_device(char *host, size_t size) override {
    const auto offset = host - output.data();
    require(offset >= 0 && size <= output.size() - offset,
            "invalid output mirror span");
    for (size_t i = 0; i < size; i += 8) {
      const auto value = sim.predicted[memory_base + 0x30000 + offset + i];
      std::memcpy(host + i, &value, 8);
    }
    ++pulls;
  }
};
int main(int argc, char **argv) {
  try {
    require(argc == 2, "scenario required");
    const std::string scenario = argv[1];
    Simulation sim(scenario == "roundtrip-stalls");
    PredictedStreamIO io(sim);
    auto in = sink_parameters();
    auto out = source_parameters();
    require(in.buffer_capacity == 8192 && out.buffer_capacity == 8192,
            "generated ring capacity");
    FPGAManagedStreams::CPUToFPGADriver input(
        std::move(in), io.input.data(), 0x20000, io);
    FPGAManagedStreams::FPGAToCPUDriver output(
        std::move(out), io.output.data(), 0x30000, io);
    input.init();
    output.init();
    const unsigned tokens = scenario == "ring-wrap" ? 385 : 67;
    unsigned pushed = 0, pulled = 0;
    std::array<uint64_t, 8 * 32> tx{}, rx{};
    for (unsigned iteration = 0; iteration < 10000 && pulled < tokens;
         ++iteration) {
      if (pushed < tokens) {
        const unsigned count = std::min(32u, tokens - pushed);
        for (unsigned t = 0; t < count; ++t)
          for (unsigned i = 0; i < 8; ++i)
            tx[t * 8 + i] =
                0xcafe000000000000ULL | uint64_t(pushed + t) << 16 | i;
        pushed += input.push(tx.data(), count * 64, 0) / 64;
      }
      // Pull clamp and noncoherent refresh are exercised while both engines
      // run.
      const unsigned count = output.pull(rx.data(), rx.size() * 8, 0) / 64;
      for (unsigned t = 0; t < count; ++t)
        for (unsigned i = 0; i < 8; ++i)
          require(rx[t * 8 + i] ==
                      (0xcafe000000000000ULL | uint64_t(pulled + t) << 16 | i),
                  "full platform payload mismatch");
      pulled += count;
      for (unsigned i = 0; i < 17; ++i)
        sim.cycle();
    }
    require(pushed == tokens && pulled == tokens,
            "native roundtrip stalled or lost tokens");
    input.flush();
    output.flush();
    require(io.pushes && io.pulls,
            "noncoherent mirror synchronization not exercised");
    std::cout << "PASS PREDICTED full platform " << scenario
              << " tokens=" << tokens << " DDR_reads=" << sim.reads
              << " DDR_writes=" << sim.writes << '\n';
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
