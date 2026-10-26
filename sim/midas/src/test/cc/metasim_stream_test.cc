// See LICENSE for license details.
// Predicted MMIO/device responses; production simif_emul memory and stream IO.
#include "core/simulation.h"
#include "core/v80_slash_ddr_stream.h"
#include "core/v80_slash_mmio_stream.h"
#include "emul/simif_emul.h"
#include <array>
#include <deque>
#include <limits>
#include <stdexcept>
#include <string>

// The lifecycle is outside this focused transport regression. No driver thread,
// RTL simulator, SDK, device, or target program is launched by these stubs.
simif_t::simif_t(const TargetConfig &config) : config(config) {}
simif_t::~simif_t() = default;
int simif_t::run(simulation_t &) {
  throw std::runtime_error("Unused lifecycle");
}
void simulation_t::simulation_init() {
  throw std::runtime_error("Unused lifecycle");
}
void simulation_t::simulation_finish() {
  throw std::runtime_error("Unused lifecycle");
}
int simulation_t::execute_simulation_flow() {
  throw std::runtime_error("Unused lifecycle");
}
CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() {
  throw std::runtime_error("Missing CPU transport");
}
FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() {
  throw std::runtime_error("Missing FPGA transport");
}

static void check(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}
template <class F>
static void rejects(F f) {
  bool failed = false;
  try {
    f();
  } catch (const std::exception &) {
    failed = true;
  }
  check(failed, "Invalid stream operation was accepted");
}

class Model : public simif_emul_t {
public:
  unsigned accesses = 0;
  std::map<size_t, uint32_t> regs;
  std::deque<char> bytes;
  Model(const TargetConfig &config, std::vector<std::string> args = {})
      : simif_emul_t(config, args) {}
  void write(size_t address, uint32_t value) override {
    ++accesses;
    regs[address] = value;
#ifdef V80_DDR_STREAM_METADATA
    if (address == V80_DDR_STREAM_COMMAND) {
      const uint64_t offset = regs[V80_DDR_STREAM_DDR_LO] |
                              (uint64_t(regs[V80_DDR_STREAM_DDR_HI]) << 32);
      const size_t count = regs[V80_DDR_STREAM_BYTES];
      check(offset == V80_DDR_STREAM_MIN_DDR && count <= 2 * 1024 * 1024,
            "Incorrect predicted DMA command");
      auto *data = static_cast<char *>(slave[0]->get_data()) + offset;
      if (value == 1)
        bytes.insert(bytes.end(), data, data + count);
      else {
        check(value == 2 && bytes.size() >= count, "Predicted DMA underflow");
        for (size_t i = 0; i < count; ++i) {
          data[i] = bytes.front();
          bytes.pop_front();
        }
      }
    }
#elif defined(V80_MMIO_STREAM_METADATA)
    if (address == V80_MMIO_STREAM_TX) {
      const auto *data = reinterpret_cast<const char *>(&value);
      bytes.insert(bytes.end(), data, data + 4);
    }
#endif
  }
  uint32_t read(size_t address) override {
    ++accesses;
#ifdef V80_DDR_STREAM_METADATA
    if (address == V80_DDR_STREAM_STATUS)
      return 2;
#elif defined(V80_MMIO_STREAM_METADATA)
    if (address == V80_MMIO_STREAM_RX) {
      check(bytes.size() >= 4, "Predicted MMIO underflow");
      uint32_t word;
      auto *data = reinterpret_cast<char *>(&word);
      for (size_t i = 0; i < 4; ++i) {
        data[i] = bytes.front();
        bytes.pop_front();
      }
      return word;
    }
#endif
    return regs[address];
  }
};

int main(int argc, char **argv) {
  try {
    check(argc == 2, "Expected one test scenario");
    const std::string name(argv[1]);
    const bool cpu = name == "legacy-cpu", fpga = name == "legacy-fpga";
    const bool shared = name == "shared-rings" || name == "shared-overflow" ||
                        name == "shared-small" || name == "shared-channels";
    const TargetConfig config{
        .ctrl = {1, 12, 32},
        .mem = {4, 22, 64},
        .mem_num_channels = name == "shared-channels" ? 2U : 1U,
        .cpu_managed =
            cpu ? std::optional<AXI4Config>({1, 32, 512}) : std::nullopt,
        .fpga_managed =
            fpga ? std::optional<AXI4Config>({4, 64, 512}) : std::nullopt,
        .qsfp = {0, 0},
        .target_name = "predicted-transport",
        .fpga_managed_ddr = shared,
        .target_dram_bytes = name == "shared-small" ? 8U * 1024 * 1024 : 65536};
    if (name == "shared-small" || name == "shared-channels") {
      rejects([&] { Model model(config); });
    } else {
      Model model(config,
                  name == "small-staging"
                      ? std::vector<std::string>{"+memsize=65536"}
                      : std::vector<std::string>{});
      check(bool(model.get_cpu_managed_axi4()) == cpu,
            "External CPU AXI port does not match generated config");
      check(bool(model.get_fpga_managed_axi4()) == fpga,
            "External FPGA AXI port does not match generated config");
      if (shared || fpga) {
        auto &io = model.get_fpga_managed_stream_io();
        if (name == "shared-overflow") {
          rejects([&] {
            io.allocate_to_cpu_buffer(std::numeric_limits<size_t>::max());
          });
          rejects([&] { io.allocate_to_cpu_buffer(0); });
        }
        auto a = io.allocate_to_cpu_buffer(8192);
        auto b = io.allocate_from_cpu_buffer(8192);
        check(a.cpu && b.cpu && a.fpga % 4096 == 0 && b.fpga >= a.fpga + 8192,
              "Ring alignment or independence");
        if (shared) {
          check(a.fpga == 65536 && b.fpga == 73728,
                "Ring overlaps target memory");
          auto *backing = static_cast<char *>(model.slave[0]->get_data());
          check(a.cpu == backing + a.fpga && b.cpu == backing + b.fpga,
                "DDR rings do not share the actual modeled memory");
          static_cast<char *>(a.cpu)[37] = 0x5a;
          check(backing[a.fpga + 37] == 0x5a,
                "Ring write not visible to memory model");
          const auto remainder = model.slave[0]->get_size() - b.fpga - 8192;
          io.allocate_to_cpu_buffer(remainder);
          rejects([&] { io.allocate_from_cpu_buffer(8192); });
        } else {
          check(a.fpga == 0 &&
                    model.get_fpga_managed_axi4()->get_size() == (1ULL << 30),
                "Legacy 64-bit PCIM mapping changed");
        }
      } else if (name == "no-stream") {
        rejects([&] { model.get_cpu_managed_stream_io(); });
        rejects([&] { model.get_fpga_managed_stream_io(); });
      } else {
        auto &io = model.get_cpu_managed_stream_io();
        if (name == "zero" || cpu) {
          check(!io.cpu_managed_axi4_write(0, nullptr, 0) &&
                    !io.cpu_managed_axi4_read(0, nullptr, 0) && !model.accesses,
                "Zero request touched a transport");
        } else if (name == "invalid" || name == "small-staging") {
          std::array<char, 192> data{};
          if (name == "small-staging") {
            rejects([&] {
              io.cpu_managed_axi4_write(0, data.data(), data.size());
            });
          } else {
            rejects([&] { io.cpu_managed_axi4_write(1, data.data(), 64); });
            rejects([&] { io.cpu_managed_axi4_read(0, data.data(), 63); });
            rejects([&] { io.cpu_managed_axi4_read(0, nullptr, 64); });
          }
          check(!model.accesses, "Rejected transfer changed MMIO state");
        } else if (name == "roundtrip") {
          std::array<char, 192> source, dest{};
          for (size_t i = 0; i < source.size(); ++i)
            source[i] = char(i * 17 + 3);
          auto *backing = static_cast<char *>(model.slave[0]->get_data());
          std::memset(backing, 0x6c, 65536);
          check(io.cpu_managed_axi4_write(0, source.data(), source.size()) ==
                    source.size(),
                "Short stream push");
          check(io.cpu_managed_axi4_read(0, dest.data(), dest.size()) ==
                    dest.size(),
                "Short stream pull");
          check(source == dest && model.bytes.empty(), "Stream bytes changed");
          for (unsigned i = 0; i < 65536; ++i)
            check(backing[i] == 0x6c, "Stream staging overwrote target memory");
        } else {
          throw std::invalid_argument("Unknown metasimulation scenario");
        }
      }
    }
    std::printf("%s: PASS (predicted transport; no hardware)\n", name.c_str());
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
