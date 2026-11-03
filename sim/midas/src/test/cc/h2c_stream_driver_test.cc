#include "bridges/fpga_managed_stream.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <utility>
#include <vector>

class MockIO final : public FPGAManagedStreamIO {
public:
  std::array<char, 512> staging{};
  uint32_t queued = 0;
  uint32_t done = 0;
  uint32_t error = 0;
  std::vector<std::pair<uint64_t, uint32_t>> writes;

  uint32_t mmio_read(size_t addr) override {
    if (addr == 0x10) return queued;
    if (addr == 0x20) return done;
    if (addr == 0x30) return error;
    assert(false);
    return 0;
  }
  void mmio_write(size_t, uint32_t) override { assert(false); }
  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t) override {
    assert(false);
    return {};
  }
  void pcis_write32(uint64_t addr, uint32_t value) override {
    assert(addr == (writes.size() % 4) * 4);
    writes.emplace_back(addr, value);
  }
  void expect_descriptor(size_t number, uint64_t address, uint32_t length,
                         uint32_t stream_id) {
    const auto base = number * 4;
    assert(writes.size() >= base + 4);
    assert(writes[base].second == static_cast<uint32_t>(address));
    assert(writes[base + 1].second == static_cast<uint32_t>(address >> 32));
    assert(writes[base + 2].second == length);
    assert(writes[base + 3].second == stream_id);
  }
};

int main() {
  MockIO io;
  std::array<char, 128> a{}, b{}, c{};
  for (size_t i = 0; i < a.size(); ++i) {
    a[i] = static_cast<char>(i);
    b[i] = static_cast<char>(i + 1);
    c[i] = static_cast<char>(i + 2);
  }

  FPGAManagedStreams::CPUToFPGADriver driver(
      FPGAManagedStreams::H2CStreamParameters("test", 0, 4, 0x10, 0x20, 0x30),
      {io.staging.data(), 0x1200004000ULL}, io);
  assert(driver.push(a.data(), 128, 64) == 128);
  io.expect_descriptor(0, 0x1200004000ULL, 128, 0);
  assert(std::memcmp(io.staging.data(), a.data(), 128) == 0);
  assert(io.done == 0); // push returns before DMA completion.
  assert(driver.push(b.data(), 128, 64) == 128);
  io.expect_descriptor(1, 0x1200004100ULL, 128, 0);
  assert(std::memcmp(io.staging.data() + 256, b.data(), 128) == 0);
  assert(driver.push(c.data(), 128, 64) == 0); // Both staging slots are live.
  io.done = 1;
  assert(driver.push(c.data(), 128, 64) == 128);
  io.expect_descriptor(2, 0x1200004000ULL, 128, 0);
  assert(std::memcmp(io.staging.data(), c.data(), 128) == 0);
  io.done = 3;
  driver.flush();
  io.queued = 4;
  assert(driver.push(a.data(), 128, 64) == 0);
  io.queued = 3;
  assert(driver.push(a.data(), 128, 128) == 0);
  io.error = 1;
  try {
    driver.push(a.data(), 128, 64);
    assert(false);
  } catch (const std::runtime_error &) {
  }

  MockIO second_io;
  FPGAManagedStreams::CPUToFPGADriver second(
      FPGAManagedStreams::H2CStreamParameters("second", 1, 4, 0x10, 0x20, 0x30),
      {second_io.staging.data(), 0x1300004000ULL}, second_io);
  assert(second.push(a.data(), 128, 64) == 128);
  second_io.expect_descriptor(0, 0x1300004000ULL, 128, 1);
  second_io.done = 1;
  second.flush();
}
