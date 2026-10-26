// See LICENSE for license details.
// PREDICTED host/device memory and MMIO; no board access.
#include "bridges/fpga_managed_stream.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>

namespace {
void require(bool v, const char *s) {
  if (!v)
    throw std::runtime_error(s);
}
template <typename F>
void rejects(F f) {
  bool caught = false;
  try {
    f();
  } catch (const std::exception &) {
    caught = true;
  }
  require(caught, "invalid operation accepted");
}
class PredictedIO : public FPGAManagedStreamIO {
public:
  std::vector<char> host, device;
  std::map<size_t, uint32_t> regs;
  std::vector<std::pair<size_t, size_t>> spans;
  std::vector<uint32_t> bells;
  uint32_t total = 0;
  bool stuck = false, consume = true, flush_done = true;
  explicit PredictedIO(size_t size)
      : host(size, char(0xcc)), device(size, char(0xdd)) {}
  FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t) override {
    return {host.data(), 0x100001000ULL};
  }
  uint32_t mmio_read(size_t addr) override {
    if (addr == 16)
      return stuck ? regs[16] : 0;
    if (addr == 20)
      return regs[20];
    if (addr == 32)
      return flush_done;
    return regs[addr];
  }
  void mmio_write(size_t addr, uint32_t value) override {
    regs[addr] = value;
    if (addr == 16) {
      // Publication must already have reached the predicted device before
      // doorbell.
      require(host == device, "doorbell preceded noncoherent publication");
      bells.push_back(value);
      total += value;
      if (consume)
        regs[20] = total;
    }
  }
  void sync_to_device(char *p, size_t size) override {
    const size_t offset = p - host.data();
    require(offset <= host.size() && size <= host.size() - offset,
            "span exceeds ring");
    spans.emplace_back(offset, size);
    std::memcpy(device.data() + offset, p, size);
  }
};
FPGAManagedStreams::FromCPUStreamParameters parameters(size_t size) {
  return {"PREDICTED-input", uint32_t(size), 0, 4, 16, 20, 24, 28, 32};
}
} // namespace
int main(int argc, char **argv) {
  try {
    require(argc == 2, "scenario required");
    const std::string scenario = argv[1];
    const size_t capacity = scenario == "counter-wrap" ? 4 * 1024 * 1024 : 256;
    PredictedIO io(capacity);
    // Untouched bytes initially agree; each later transfer still must publish
    // its exact spans.
    io.device = io.host;
    FPGAManagedStreams::CPUToFPGADriver driver(parameters(capacity),
                                               io.host.data(),
                                               0x100001000ULL,
                                               io,
                                               std::chrono::milliseconds(2));
    std::vector<char> source(capacity + 64);
    for (size_t i = 0; i < source.size(); ++i)
      source[i] = char(i / 64 + i % 64);
    if (scenario == "pull-init") {
      FPGAManagedStreams::FPGAToCPUDriver output(
          {"PREDICTED-output", uint32_t(capacity), 0, 4, 16, 20, 24, 28, 32},
          io.host.data(),
          0x100001000ULL,
          io);
      output.init();
      require(io.regs[0] == 1 && io.regs[4] == 4096 && io.regs[24] == 1,
              "output base/done-init registers differ");
    } else if (scenario == "init") {
      driver.init();
      require(io.regs[0] == 1 && io.regs[4] == 4096 && io.regs[24] == 1,
              "64-bit base/done-init registers differ");
    } else if (scenario == "linear") {
      require(driver.push(source.data(), 128, 64) == 128,
              "linear count differs");
      require(
          std::equal(source.begin(), source.begin() + 128, io.device.begin()),
          "stale device data");
      require(io.spans == std::vector<std::pair<size_t, size_t>>{{0, 128}},
              "wrong publication span");
      require(io.bells == std::vector<uint32_t>{128}, "wrong doorbell");
    } else if (scenario == "wrap") {
      require(driver.push(source.data(), 192, 192) == 192, "first push failed");
      io.spans.clear();
      require(driver.push(source.data(), 128, 128) == 128, "wrap push failed");
      require(io.spans ==
                  std::vector<std::pair<size_t, size_t>>{{192, 64}, {0, 64}},
              "wrap spans differ");
      require(
          std::equal(
              source.begin(), source.begin() + 64, io.device.begin() + 192) &&
              std::equal(
                  source.begin() + 64, source.begin() + 128, io.device.begin()),
          "wrap payload differs");
    } else if (scenario == "required" || scenario == "full") {
      io.consume = false;
      require(driver.push(source.data(), 192, 192) == 192, "first push failed");
      const auto bells = io.bells.size(), spans = io.spans.size();
      require(driver.push(source.data(), 128, 128) == 0,
              "required threshold ignored");
      require(io.bells.size() == bells && io.spans.size() == spans,
              "failed threshold changed state");
      require(driver.push(source.data(), 128, 0) == 64,
              "free capacity not clamped");
      require(driver.push(source.data(), 64, 0) == 0, "full ring overwritten");
    } else if (scenario == "zero") {
      require(driver.push(nullptr, 0, 0) == 0 && io.regs.empty() &&
                  io.spans.empty(),
              "zero push accessed hardware");
    } else if (scenario == "invalid") {
      rejects([&] { driver.push(source.data(), 63, 0); });
      rejects([&] { driver.push(source.data(), 64, 1); });
      rejects([&] { driver.push(source.data(), 64, 128); });
      rejects([&] { driver.push(nullptr, 64, 0); });
      require(io.regs.empty() && io.spans.empty(),
              "invalid args accessed hardware");
    } else if (scenario == "invalid-ring") {
      rejects([&] {
        FPGAManagedStreams::CPUToFPGADriver d(
            parameters(192), io.host.data(), 4096, io);
      });
      rejects([&] {
        FPGAManagedStreams::CPUToFPGADriver d(
            parameters(256), io.host.data(), 4097, io);
      });
      rejects([&] {
        FPGAManagedStreams::CPUToFPGADriver d(
            parameters(256), nullptr, 4096, io);
      });
      rejects([&] {
        FPGAManagedStreams::CPUToFPGADriver d(parameters(256),
                                              io.host.data(),
                                              4096,
                                              io,
                                              std::chrono::milliseconds(0));
      });
    } else if (scenario == "corrupt-credit" || scenario == "unaligned-credit") {
      io.regs[20] = scenario == "unaligned-credit" ? 1 : 64;
      rejects([&] { driver.push(source.data(), 64, 0); });
      rejects([&] { driver.push(source.data(), 64, 0); });
      require(io.spans.empty(), "corrupt credit changed memory");
    } else if (scenario == "doorbell-timeout") {
      io.stuck = true;
      rejects([&] { driver.push(source.data(), 64, 0); });
      rejects([&] { driver.push(source.data(), 64, 0); });
      require(io.bells.size() == 1, "failed doorbell retried");
    } else if (scenario == "consume-timeout" || scenario == "flush-timeout") {
      io.consume = scenario != "consume-timeout";
      io.flush_done = false;
      require(driver.push(source.data(), 64, 64) == 64, "initial push failed");
      rejects([&] { driver.flush(); });
      rejects([&] { driver.flush(); });
      require((scenario != "consume-timeout") || io.regs.count(28) == 0,
              "flush before consumed");
    } else if (scenario == "flush") {
      require(driver.push(source.data(), 64, 64) == 64, "initial push failed");
      driver.flush();
      require(io.regs[28] == 0, "flush request not cleared");
      require(driver.push(source.data(), 64, 64) == 64,
              "push after flush failed");
      driver.flush();
    } else if (scenario == "counter-wrap") {
      for (unsigned i = 0; i < 1025; ++i)
        require(driver.push(source.data(), capacity, capacity) == capacity,
                "32-bit counter wrap lost capacity");
      require(io.total == capacity, "predicted consumption did not wrap");
      driver.flush();
    } else
      throw std::runtime_error("unknown scenario");
    std::cout << "PASS predicted FPGA-managed push " << scenario << '\n';
    return 0;
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 1;
  }
}
