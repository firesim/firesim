// See LICENSE for license details.
// Actual production MMIO helper and CPU stream drivers; predicted
// DDR/primitives.
#define SLASH_FPGA_STREAM_TEST
#include "bridges/cpu_managed_stream.h"
#include "core/v80_slash_mmio_stream.h"
#include "driver-parameters.h"
#include "slash_platform_test.cc"
#include <array>

// Model the previously observed 16-byte NoC read suffix as sequential AXI-Lite
// accesses. This is a transaction policy, not a model of physical PCIe/NoC.
class SuffixReadIO {
public:
  Simulation &sim;
  bool enabled;
  SuffixReadIO(Simulation &s, bool expand) : sim(s), enabled(expand) {}
  void write(size_t address, uint32_t data) { sim.write(address, data); }
  uint32_t read(size_t address) {
    const auto value = sim.read(address);
    if (enabled)
      for (auto next = address + 4; next < (address & ~size_t(15)) + 16;
           next += 4)
        sim.read(next);
    return value;
  }
};

class MMIOStreamIO : public CPUManagedStreamIO {
public:
  Simulation &sim;
  SuffixReadIO read_io;
  explicit MMIOStreamIO(Simulation &s, bool suffix)
      : sim(s), read_io(s, suffix) {}
  uint32_t mmio_read(size_t address) override { return sim.read(address); }
  size_t
  cpu_managed_axi4_read(size_t address, char *data, size_t size) override {
    return v80_slash_mmio_stream::pull(read_io, address, data, size);
  }
  size_t cpu_managed_axi4_write(size_t address,
                                const char *data,
                                size_t size) override {
    return v80_slash_mmio_stream::push(sim, address, data, size);
  }
  uint64_t get_beat_bytes() const override { return 4; }
};
int main(int argc, char **argv) {
  try {
    require(argc == 2, "scenario required");
    const std::string scenario = argv[1];
    require(scenario == "roundtrip" || scenario == "roundtrip-stalls" ||
                scenario == "partial-pulls" || scenario == "read-suffix" ||
                scenario == "read-suffix-stalls",
            "unknown scenario");
    Simulation sim(scenario.find("stalls") != std::string::npos);
    MMIOStreamIO io(sim, scenario.find("read-suffix") == 0);
    CPUManagedStreams::CPUToFPGADriver input(sink_parameters(), io);
    CPUManagedStreams::FPGAToCPUDriver output(source_parameters(), io);
    input.init();
    output.init();
    const auto before = sim.cycles;
    require(v80_slash_mmio_stream::push(sim, 0, nullptr, 0) == 0 &&
                v80_slash_mmio_stream::pull(sim, 0, nullptr, 0) == 0 &&
                sim.cycles == before,
            "zero helper accessed card");
    unsigned rejected = 0;
    std::array<uint64_t, 8 * 4> tx{}, rx{};
    for (auto address : {uint64_t(1), uint64_t(63), uint64_t(1) << 32}) {
      try {
        v80_slash_mmio_stream::push(
            sim, address, reinterpret_cast<char *>(tx.data()), 64);
      } catch (const std::invalid_argument &) {
        ++rejected;
      }
      try {
        v80_slash_mmio_stream::pull(
            sim, address, reinterpret_cast<char *>(rx.data()), 64);
      } catch (const std::invalid_argument &) {
        ++rejected;
      }
    }
    require(rejected == 6 && sim.cycles == before,
            "invalid helper touched registers");
    unsigned pushed = 0, pulled = 0;
    const unsigned tokens = 97;
    for (unsigned iteration = 0; iteration < 1000 && pulled < tokens;
         ++iteration) {
      if (pushed < tokens) {
        const auto count = std::min(4u, tokens - pushed);
        for (unsigned t = 0; t < count; ++t)
          for (unsigned i = 0; i < 8; ++i)
            tx[t * 8 + i] =
                0xbeef000000000000ULL | uint64_t(pushed + t) << 16 | i;
        pushed += input.push(tx.data(), count * 64, 0) / 64;
      }
      const auto count =
          output.pull(rx.data(), scenario == "partial-pulls" ? 64 : 256, 0) /
          64;
      for (unsigned t = 0; t < count; ++t)
        for (unsigned i = 0; i < 8; ++i)
          require(rx[t * 8 + i] ==
                      (0xbeef000000000000ULL | uint64_t(pulled + t) << 16 | i),
                  "MMIO token data/order");
      pulled += count;
      for (unsigned i = 0; i < 9; ++i)
        sim.cycle();
    }
    require(pushed == tokens && pulled == tokens,
            "MMIO transport lost/stalled tokens");
    require(sim.reads == 0 && sim.writes == 0,
            "MMIO transport unexpectedly touched external DDR");
    input.flush();
    output.flush();
    std::cout << "PASS PREDICTED full MMIO platform " << scenario
              << " tokens=" << tokens << '\n';
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
