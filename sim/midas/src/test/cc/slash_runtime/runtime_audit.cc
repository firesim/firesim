// See LICENSE for license details.
// Compile the original runtime unchanged against explicit SDK test doubles.
#include V80_RUNTIME_SOURCE
#include <limits>

#ifdef CANDIDATE_RUNTIME
using Runtime = simif_xilinx_alveo_v80_slash_t;
#else
using Runtime = simif_xilinx_v80_t;
#endif

uint64_t place_buffer(FPGAManagedStreamIO &io, size_t bytes, char **host) {
#ifdef CANDIDATE_RUNTIME
  auto buffer = io.allocate_to_cpu_buffer(bytes);
  *host = static_cast<char *>(buffer.cpu);
  return buffer.fpga;
#else
  return io.allocate_stream_buffer(bytes, host);
#endif
}

// Only provide base-class plumbing. Every tested allocation, control, capture
// and DMA-copy operation executes the original simif_xilinx_v80.cc above.
simif_t::simif_t(const TargetConfig &config) : config(config) {}
simif_t::~simif_t() = default;
CPUManagedStreamIO &simif_t::get_cpu_managed_stream_io() {
  throw std::runtime_error("unexpected base CPU streams");
}
FPGAManagedStreamIO &simif_t::get_fpga_managed_stream_io() {
  throw std::runtime_error("unexpected base FPGA streams");
}
int simif_t::run(simulation_t &) {
  throw std::runtime_error("no simulation in CPU audit");
}
std::string_view simif_t::get_target_name() const { return config.target_name; }

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
  require(threw, "invalid operation was accepted");
}
void expect(size_t address, const std::vector<uint8_t> &expected) {
  for (size_t i = 0; i < expected.size(); ++i)
    require(vrt::predicted_card[vrt::base + address + i] == expected[i],
            "predicted card bytes differ from ordered write reference");
}

int main(int argc, char **argv) {
  try {
    require(argc == 2, "expected one case");
    const std::string name = argv[1];
#ifdef CANDIDATE_RUNTIME
    if (name.rfind("no-ddr-", 0) == 0 || name == "invalid-memory-config") {
      const TargetConfig no_ddr{{1, 12, 32}, {1, 0, 64}, 0, {}, {}, {},
                          "cpu-runtime-no-ddr-audit"};
      const std::vector<std::string> args{
          "+binary_file=cpu-model.vbin", "+v80-use-loaded-image"};
      if (name == "invalid-memory-config") {
        for (unsigned channels : {1u, 2u}) {
          const TargetConfig invalid_map{{1, 12, 32}, {1, 0, 64}, channels,
                                         {}, {}, {}, "invalid-memory-audit"};
          rejected([&] { Runtime invalid(invalid_map, args); });
        }
        require(!vrt::device_opens, "invalid memory map opened SDK device");
      } else if (name == "no-ddr-reservation") {
        auto invalid_args = args;
        invalid_args.push_back("+dram_alloc=4096");
        rejected([&] { Runtime invalid(no_ddr, invalid_args); });
        require(!vrt::device_opens, "invalid reservation opened SDK device");
      } else {
#ifdef V80_DDR_STREAM_METADATA
        rejected([&] { Runtime invalid(no_ddr, args); });
        require(!vrt::device_opens, "DDR stream without DDR opened SDK device");
#else
        Runtime sim(no_ddr, args);
        sim.write(0x100, 7);
        require(sim.read(0x100) == 0x46697265, "MMIO-only control access");
        std::vector<uint8_t> bytes(64, 0x12);
        rejected([&] { sim.write_dram_bulk(0, bytes.data(), bytes.size()); });
        char *host = nullptr;
        rejected([&] { place_buffer(sim.get_fpga_managed_stream_io(), 4096, &host); });
#ifdef V80_MMIO_STREAM_METADATA
        auto &io = sim.get_cpu_managed_stream_io();
        require(io.cpu_managed_axi4_write(0, reinterpret_cast<char *>(bytes.data()), 64) == 64,
                "MMIO-only stream push");
        require(io.cpu_managed_axi4_read(0, reinterpret_cast<char *>(bytes.data()), 64) == 64,
                "MMIO-only stream pull");
#endif
#endif
      }
      require(!vrt::allocations && vrt::transfers.empty(),
              "zero-DDR case allocated or transferred DDR data");
      std::cout << "PASS PREDICTED runtime " << name << '\n';
      return 0;
    }
#endif
    const TargetConfig config{{1, 12, 32},
                              {1, name == "design-small" ? 25u : 33u, 64},
                              1,
                              {},
                              {},
                              {},
                              "cpu-runtime-audit"};
    Runtime sim(config,
                {"+binary_file=cpu-model.vbin", "+v80-use-loaded-image"});
    std::vector<uint8_t> a(64, 0x11), b(64, 0x22);
#ifdef V80_DDR_STREAM_METADATA
    if (name == "cpu-stream-zero") {
      auto &io = sim.get_cpu_managed_stream_io();
      require(!io.cpu_managed_axi4_write(3, nullptr, 0), "empty push count");
      require(!io.cpu_managed_axi4_read(3, nullptr, 0), "empty pull count");
      require(!vrt::allocations && vrt::registers.empty(),
              "empty stream had effects");
    } else if (name == "cpu-stream-invalid") {
      auto &io = sim.get_cpu_managed_stream_io();
      rejected([&] {
        io.cpu_managed_axi4_write(1, reinterpret_cast<char *>(a.data()), 64);
      });
      rejected([&] {
        io.cpu_managed_axi4_read(0, reinterpret_cast<char *>(a.data()), 65);
      });
      rejected([&] { io.cpu_managed_axi4_write(0, nullptr, 64); });
      rejected([&] { io.cpu_managed_axi4_read(0, nullptr, 64); });
      require(!vrt::allocations && vrt::registers.empty(),
              "invalid stream had effects");
    } else if (name == "cpu-stream-push" || name == "cpu-stream-pull" ||
               name == "cpu-stream-pending-target") {
      const bool pending = name == "cpu-stream-pending-target";
      const size_t target_address = 8u << 20;
      if (pending)
        sim.write_dram_bulk(target_address, b.data(), b.size());
      auto &io = sim.get_cpu_managed_stream_io();
      io.cpu_managed_axi4_write(
          128, reinterpret_cast<char *>(a.data()), a.size());
      require(vrt::registers.size() == 6, "stream command/register count");
      require(
          std::get<1>(vrt::registers[0]) == 0x1000 + V80_DDR_STREAM_DDR_LO &&
              std::get<1>(vrt::registers[1]) == 0x1000 + V80_DDR_STREAM_DDR_HI,
          "stream staging address metadata");
      const uint64_t staging = std::get<2>(vrt::registers[0]) |
                               (uint64_t(std::get<2>(vrt::registers[1])) << 32);
      require(staging >= V80_DDR_STREAM_MIN_DDR, "stream overlaps target");
      expect(staging, a);
      if (pending)
        expect(target_address, b);
      if (name == "cpu-stream-pull") {
        for (size_t i = 0; i < b.size(); ++i)
          vrt::predicted_card[vrt::base + staging + i] = b[i];
        io.cpu_managed_axi4_read(
            128, reinterpret_cast<char *>(a.data()), a.size());
        require(a == b, "stream did not refresh staging from predicted card");
      }
    } else
#endif
        if (name == "single" || name == "copy-ownership") {
      sim.write_dram_bulk(128, a.data(), a.size());
      if (name == "copy-ownership")
        a.assign(a.size(), 0x33);
      sim.flush_dram_writes();
      expect(128, std::vector<uint8_t>(64, 0x11));
    } else if (name == "same-offset-shorter") {
      sim.write_dram_bulk(128, a.data(), a.size());
      sim.write_dram_bulk(128, b.data(), 16);
      sim.flush_dram_writes();
      std::fill(a.begin(), a.begin() + 16, 0x22);
      expect(128, a);
    } else if (name == "reverse-overlap" || name == "ascending-overlap") {
      const size_t first = name == "reverse-overlap" ? 128 : 96;
      const size_t second = name == "reverse-overlap" ? 96 : 128;
      sim.write_dram_bulk(first, a.data(), a.size());
      sim.write_dram_bulk(second, b.data(), b.size());
      sim.flush_dram_writes();
      std::vector<uint8_t> reference(96, 0);
      std::fill(
          reference.begin() + first - 96, reference.begin() + first - 32, 0x11);
      std::fill(reference.begin() + second - 96,
                reference.begin() + second - 32,
                0x22);
      expect(96, reference);
    } else if (name == "zero-length") {
      sim.write_dram_bulk(0, a.data(), 0);
      sim.flush_dram_writes();
      require(vrt::allocations == 0 && vrt::transfers.empty(),
              "zero write allocated or transferred");
    } else if (name == "aperture-limit") {
      rejected([&] { sim.write_dram_bulk(32ull << 30, a.data(), a.size()); });
    } else if (name == "address-overflow") {
      rejected([&] {
        sim.write_dram_bulk(
            std::numeric_limits<size_t>::max() - 31, a.data(), a.size());
      });
    } else if (name == "flush-before-read" || name == "flush-before-write") {
      sim.write_dram_bulk(128, a.data(), a.size());
      require(vrt::transfers.empty(), "write was not deferred");
      if (name == "flush-before-read")
        sim.read(0x40);
      else
        sim.write(0x40, 1);
      expect(128, a);
      require(vrt::registers.size() == 1 &&
                  std::get<1>(vrt::registers[0]) == 0x1040,
              "control alias mismatch");
    } else if (name == "design-bulk-limit") {
      rejected([&] { sim.write_dram_bulk((8ull << 30) - 32, a.data(), 64); });
      rejected([&] { sim.write_dram_bulk(8ull << 30, a.data(), 64); });
      require(!vrt::allocations, "out-of-design bulk write allocated memory");
    } else if (name == "design-bulk-last") {
      require(sim.write_dram_bulk((8ull << 30) - 64, a.data(), 64),
              "last valid design span rejected");
      require(!vrt::allocations, "capture unexpectedly flushed");
    } else if (name == "design-small") {
      auto &io = sim.get_fpga_managed_stream_io();
      char *host = nullptr;
      rejected([&] { place_buffer(io, 4096, &host); });
      require(!vrt::allocations, "impossible pool allocated target memory");
    }
#ifdef CANDIDATE_RUNTIME
    else if (name == "control-reserved") {
      sim.write_dram_bulk(128, a.data(), a.size());
      unsigned blocked = 0;
      for (size_t address = 0; address < 0x40; address += 4) {
        try {
          sim.read(address);
        } catch (const std::out_of_range &) {
          ++blocked;
        }
        try {
          sim.write(address, 1);
        } catch (const std::out_of_range &) {
          ++blocked;
        }
      }
      require(blocked == 32 && vrt::registers.empty() &&
                  vrt::transfers.empty() && !vrt::allocations,
              "reserved control accesses reached SDK or flushed pending DMA");
    } else if (name == "control-upper-valid") {
      sim.read(0xffc);
      sim.write(0xffc, 1);
      require(vrt::registers.size() == 2 &&
                  std::get<1>(vrt::registers[0]) == 0x1ffc &&
                  std::get<1>(vrt::registers[1]) == 0x1ffc,
              "last valid control word rejected or incorrectly aliased");
    } else if (name == "control-fence-invalid") {
      const auto opens = vrt::device_opens;
      for (const auto &address : {"4", "0x3c", "0x41", "0xfff", "0x1000"}) {
        rejected([&] {
          Runtime invalid(config,
                          {"+binary_file=cpu-model.vbin",
                           "+v80-use-loaded-image",
                           std::string("+v80-control-write-fence=") + address});
        });
      }
      require(vrt::device_opens == opens && vrt::registers.empty() &&
                  vrt::transfers.empty() && !vrt::allocations,
              "invalid fence opened SDK device or caused I/O");
    } else if (name == "control-fence-valid") {
      Runtime fenced(config,
                     {"+binary_file=cpu-model.vbin", "+v80-use-loaded-image",
                      "+v80-control-write-fence=0x40"});
      fenced.write(0xffc, 1);
      require(vrt::registers.size() == 3 &&
                  std::get<1>(vrt::registers[0]) == 0x1040 &&
                  std::get<0>(vrt::registers[1]) == 'W' &&
                  std::get<1>(vrt::registers[1]) == 0x1ffc &&
                  std::get<1>(vrt::registers[2]) == 0x1040,
              "valid fence lost presence validation or write ordering");
    }
#endif
    else if (name == "control-range") {
      rejected([&] { sim.write(0x1000, 1); });
      rejected([&] { sim.read(0x1000); });
      require(vrt::registers.empty(),
              "invalid control access reached model kernel");
    } else if (name == "control-alignment") {
      rejected([&] { sim.write(3, 1); });
      rejected([&] { sim.read(3); });
    } else if (name == "split-512m" || name == "tail-1g") {
      size_t address =
          name == "split-512m" ? (512ull << 20) - 32 : (1ull << 30) - 64;
      sim.write_dram_bulk(address, a.data(), a.size());
      sim.flush_dram_writes();
      expect(address, a);
      require(vrt::allocations == 2, "expected two allocation regions");
      require(vrt::transfers.size() == (name == "split-512m" ? 2u : 1u),
              "DMA was not split at the modeled allocator boundary");
    } else if (name == "growth-after-rings") {
      auto &io = sim.get_fpga_managed_stream_io();
      char *host;
      place_buffer(io, 4096, &host);
      sim.write_dram_bulk(0, a.data(), a.size());
      rejected([&] { sim.write_dram_bulk(4u << 20, b.data(), b.size()); });
      sim.flush_dram_writes();
      expect(0, a);
    } else if (name == "ring-zero-size") {
      auto &io = sim.get_fpga_managed_stream_io();
      char *host;
      rejected([&] { place_buffer(io, 0, &host); });
    } else if (name == "allocation-failure-null" ||
               name == "allocation-failure-throw" || name == "regrow-failure" ||
               name == "second-region-failure" || name == "wrong-target-base" ||
               name == "nonconsecutive-regions") {
      if (name == "regrow-failure") {
        sim.write_dram_bulk(128, a.data(), a.size());
        sim.flush_dram_writes();
      }
      const bool two_regions =
          name == "second-region-failure" || name == "nonconsecutive-regions";
      const size_t address = two_regions ? (512ull << 20) : (4u << 20);
      sim.write_dram_bulk(address, b.data(), b.size());
      if (name == "wrong-target-base" || name == "nonconsecutive-regions") {
        vrt::misplace_allocation =
            vrt::allocation_attempts + (two_regions ? 2 : 1);
        vrt::misplaced_address = vrt::base + 4096;
      } else {
        vrt::fail_allocation = vrt::allocation_attempts + (two_regions ? 2 : 1);
        vrt::fail_with_null = name == "allocation-failure-null";
      }
      rejected([&] { sim.flush_dram_writes(); });
      require(vrt::active == 0, "failed allocation retained allocator blocks");
      sim.flush_dram_writes();
      expect(address, b);
      if (name == "regrow-failure")
        expect(128, a);
    } else if (name == "stream-allocation-failure" ||
               name == "stream-outside-aperture" ||
               name == "stream-outside-design" ||
               name == "stream-crosses-design" ||
               name == "stream-last-design-pool") {
      // First claim target memory, so the fault affects only the stream pool.
      sim.write_dram_bulk(128, a.data(), a.size());
      sim.flush_dram_writes();
      auto &io = sim.get_fpga_managed_stream_io();
      char *host = nullptr;
      if (name == "stream-allocation-failure")
        vrt::fail_allocation = vrt::allocation_attempts + 1;
      else {
        vrt::misplace_allocation = vrt::allocation_attempts + 1;
        vrt::misplaced_address = name == "stream-outside-design"
                                     ? vrt::base + (8ull << 30)
                                 : name == "stream-crosses-design"
                                     ? vrt::base + (8ull << 30) - (32ull << 20)
                                 : name == "stream-last-design-pool"
                                     ? vrt::base + (8ull << 30) - (64ull << 20)
                                     : UINT64_MAX - (32u << 20) + 1;
      }
      if (name == "stream-last-design-pool") {
        const auto address = place_buffer(io, 4096, &host);
        require(address == (8ull << 30) - (64ull << 20),
                "last valid pool offset differs");
        io.sync_to_device(host, 64);
        expect(128, a);
        return 0;
      }
      rejected([&] { place_buffer(io, 4096, &host); });
      require(vrt::active == 1, "failed stream allocation leaked a block");
      place_buffer(io, 4096, &host);
      expect(128, a);
    } else if (name == "stream-sync-range") {
      auto &io = sim.get_fpga_managed_stream_io();
      char *host = nullptr;
      place_buffer(io, 4096, &host);
      const auto end = reinterpret_cast<char *>(
          reinterpret_cast<uintptr_t>(host) + (64u << 20));
      rejected([&] { io.sync_from_device(nullptr, 1); });
      rejected([&] { io.sync_to_device(end, 1); });
      rejected([&] { io.sync_from_device(host, (64u << 20) + 1); });
      io.sync_to_device(nullptr, 0);
      require(vrt::transfers.empty(), "invalid stream sync reached SDK");
    } else if (name == "regrow-overlap") {
      sim.write_dram_bulk(128, a.data(), a.size());
      sim.flush_dram_writes();
      sim.write_dram_bulk(128, b.data(), 16);
      sim.flush_dram_writes();
      sim.write_dram_bulk(4u << 20, b.data(), b.size());
      sim.flush_dram_writes();
      std::fill(a.begin(), a.begin() + 16, 0x22);
      expect(128, a);
      expect(4u << 20, b);
    } else if (name == "ring-alignment" || name == "ring-sync" ||
               name == "ring-exhaustion") {
      auto &io = sim.get_fpga_managed_stream_io();
      char *host = nullptr;
      const size_t capacity = name == "ring-exhaustion" ? (64u << 20) : 4096;
      const uint64_t address = place_buffer(io, capacity, &host);
      require(host && address >= (2u << 20) && address % 4096 == 0,
              "stream ring overlap/alignment mismatch");
      if (name == "ring-sync") {
        std::fill(host, host + 64, char(0x5a));
        io.sync_to_device(host, 64);
        std::fill(host, host + 64, 0);
        io.sync_from_device(host, 64);
        require(std::all_of(host, host + 64, [](char c) { return c == 0x5a; }),
                "stream span sync mismatch");
      } else if (name == "ring-exhaustion") {
        rejected([&] { place_buffer(io, 4096, &host); });
      } else {
        char *second = nullptr;
        const uint64_t next = place_buffer(io, 65536, &second);
        require(next % 65536 == 0 && next >= address + capacity &&
                    second != host,
                "second ring not aligned/disjoint");
      }
    } else
      throw std::runtime_error("unknown case");
    printf("%s: PASS (predicted SDK model, no hardware)\n", name.c_str());
    return 0;
  } catch (const std::exception &error) {
    fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
