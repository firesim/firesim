// See LICENSE for license details.

#include "bridges/loadmem.h"
#include "core/simif.h"

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
const TargetConfig config{};
const LOADMEMWIDGET_struct registers{0, 4, 8, 12, 16, 20, 24, 28, 32};

void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}

struct Integer {
  mpz_t value;
  explicit Integer(const std::string &hex = "0") {
    mpz_init(value);
    require(mpz_set_str(value, hex.c_str(), 16) == 0, "invalid test integer");
  }
  ~Integer() { mpz_clear(value); }
};

std::vector<uint8_t> bytes(std::string hex, size_t length) {
  if (hex.front() == '-')
    hex.erase(0,
              1); // mpz_export writes the magnitude, as the legacy path does.
  if (hex.size() % 2)
    hex.insert(0, "0");
  std::vector<uint8_t> result(length, 0);
  for (size_t i = 0; i < length && i < hex.size() / 2; ++i)
    result[i] = std::stoul(hex.substr(hex.size() - 2 * i - 2, 2), nullptr, 16);
  return result;
}

// Deterministic register-level loadmem widget model. Sparse memory lets the
// host test exercise >4 GiB addresses without allocating a matching host array.
class WidgetDevice : public simif_t {
  uint64_t writeAddress = 0, readAddress = 0;
  size_t remaining = 0;

public:
  const unsigned words;
  std::map<uint64_t, uint8_t> memory;
  std::vector<std::string> events;
  size_t dataWrites = 0;

  explicit WidgetDevice(unsigned words) : simif_t(config), words(words) {}

  void copy(uint64_t address, const std::vector<uint8_t> &data) {
    for (size_t i = 0; i < data.size(); ++i)
      memory[address + i] = data[i];
  }
  void expect(uint64_t address, const std::vector<uint8_t> &data) const {
    for (size_t i = 0; i < data.size(); ++i) {
      auto it = memory.find(address + i);
      require((it == memory.end() ? 0 : it->second) == data[i],
              "DRAM bytes differ");
    }
  }
  void write(size_t address, uint32_t data) override {
    require(events.size() < 10000, "MMIO operation budget exceeded");
    events.push_back("write:" + std::to_string(address));
    if (address == registers.W_ADDRESS_H)
      writeAddress = (uint64_t(data) << 32) | uint32_t(writeAddress);
    else if (address == registers.W_ADDRESS_L)
      writeAddress = (writeAddress & ~uint64_t(UINT32_MAX)) | data;
    else if (address == registers.W_LENGTH) {
      require(data > 0 && data <= 1024, "unexpected MMIO burst length");
      remaining = size_t(data) * words;
    } else if (address == registers.W_DATA) {
      require(remaining > 0, "data without write length");
      for (unsigned i = 0; i < 4; ++i)
        memory[writeAddress++] = data >> (8 * i);
      --remaining;
      ++dataWrites;
    } else if (address == registers.R_ADDRESS_H)
      readAddress = (uint64_t(data) << 32) | uint32_t(readAddress);
    else if (address == registers.R_ADDRESS_L)
      readAddress = (readAddress & ~uint64_t(UINT32_MAX)) | data;
    else if (address == registers.ZERO_OUT_DRAM)
      memory.clear();
    else
      throw std::runtime_error("unknown MMIO write");
  }
  uint32_t read(size_t address) override {
    require(events.size() < 10000, "MMIO operation budget exceeded");
    events.push_back("read:" + std::to_string(address));
    if (address == registers.ZERO_FINISHED)
      return 1;
    require(address == registers.R_DATA, "unknown MMIO read");
    uint32_t result = 0;
    for (unsigned i = 0; i < 4; ++i)
      result |= uint32_t(memory[readAddress++]) << (8 * i);
    return result;
  }
};

class BulkDevice : public WidgetDevice {
  struct Span {
    uint64_t address;
    std::vector<uint8_t> data;
  };
  std::vector<Span> pending;

public:
  bool accept;
  unsigned captures = 0;
  explicit BulkDevice(unsigned words, bool accept = true)
      : WidgetDevice(words), accept(accept) {}

  // Intentionally omit override so the identical test also compiles against
  // production code predating the optional bulk API. Its bulk cases must fail.
  bool write_dram_bulk(size_t address, const void *source, size_t length) {
    if (!accept)
      return false;
    const auto *data = static_cast<const uint8_t *>(source);
    pending.push_back({address, std::vector<uint8_t>(data, data + length)});
    ++captures;
    events.push_back("capture");
    return true;
  }
  void flush_dram_writes() {
    events.push_back("flush");
    for (const auto &span : pending)
      copy(span.address, span.data);
    pending.clear();
  }
};

loadmem_t loader(WidgetDevice &device) {
  return loadmem_t(device,
                   registers,
                   0,
                   {},
                   AXI4Config{2, 40, device.words * 32u},
                   device.words);
}

const std::string pattern =
    "123456789abcdef00112233445566778899aabbccddeeff00123456789abcdef0";

void single(unsigned words, bool bulk) {
  BulkDevice fast(words);
  WidgetDevice legacy(words);
  WidgetDevice &device = bulk ? static_cast<WidgetDevice &>(fast) : legacy;
  auto bridge = loader(device);
  for (const std::string &hex :
       std::vector<std::string>{"0", "1", "12345678", "-12345678", pattern}) {
    Integer value(hex);
    const uint64_t address = 0x200000080;
    bridge.write_mem(address, value.value);
    if (bulk) {
      require(fast.captures > 0 && device.dataWrites == 0,
              "bulk single write used MMIO");
      mpz_set_ui(value.value, 0); // Caller storage need not survive the call.
      fast.flush_dram_writes();
    }
    device.expect(address, bytes(hex, words * 4));
  }
}

void chunks(unsigned words, bool bulk) {
  BulkDevice fast(words);
  WidgetDevice legacy(words);
  WidgetDevice &device = bulk ? static_cast<WidgetDevice &>(fast) : legacy;
  auto bridge = loader(device);
  const size_t beat = words * 4;
  for (size_t length : {size_t(1), beat - 1, beat, beat + 1, 3 * beat + 3}) {
    // Cross the 512 MiB allocation boundary; this stage models widget bytes,
    // not the platform allocator or physical DMA engine.
    const size_t address = (size_t(1) << 29) - beat;
    Integer value(length == 1 ? "1" : pattern);
    const size_t padded = ((length + beat - 1) / beat) * beat;
    bridge.write_mem_chunk(address, value.value, length);
    if (bulk) {
      require(fast.captures > 0 && device.dataWrites == 0,
              "bulk chunk write used MMIO");
      fast.flush_dram_writes();
    }
    device.expect(address, bytes(length == 1 ? "1" : pattern, padded));
  }
}

void declined(unsigned words) {
  BulkDevice declined(words, false);
  WidgetDevice legacy(words);
  auto a = loader(declined), b = loader(legacy);
  Integer value(pattern);
  a.write_mem(0x300000000, value.value);
  b.write_mem(0x300000000, value.value);
  a.write_mem_chunk(0x300000100, value.value, words * 8 + 1);
  b.write_mem_chunk(0x300000100, value.value, words * 8 + 1);
  // The optional backend records its no-op flushes. Compare the device/MMIO
  // trace against the backend that does not implement the optional API.
  auto device_events = declined.events;
  std::erase(device_events, "flush");
  require(declined.memory == legacy.memory && device_events == legacy.events,
          "declined transport differs from legacy MMIO");
}

void flush_read(unsigned words) {
  BulkDevice device(words);
  auto bridge = loader(device);
  Integer source(pattern), actual;
  bridge.write_mem(0x3ffff000, source.value);
  require(device.captures == 1 && device.memory.empty(),
          "write was not deferred");
  bridge.read_mem(0x3ffff000, actual.value);
  require(device.events.size() > 2 && device.events[1] == "flush",
          "read did not flush first");
  char *raw = mpz_get_str(nullptr, 16, actual.value);
  std::string hex(raw);
  void (*release)(void *, size_t);
  mp_get_memory_functions(nullptr, nullptr, &release);
  release(raw, hex.size() + 1);
  require(bytes(hex, words * 4) == bytes(pattern, words * 4),
          "read missed prior bulk write");
}

void mixed_transport(unsigned words) {
  BulkDevice device(words);
  auto bridge = loader(device);
  Integer first(pattern), second("aabbccdd");
  bridge.write_mem(0x100, first.value);
  require(device.captures == 1 && device.memory.empty(),
          "first write was not deferred");
  device.accept = false;
  bridge.write_mem(0x100, second.value);
  device.flush_dram_writes();
  device.expect(0x100, bytes("aabbccdd", words * 4));
  require(device.events[1] == "flush",
          "MMIO fallback overtook an accepted bulk write");
}

void flush_zero(unsigned words) {
  BulkDevice device(words);
  auto bridge = loader(device);
  Integer value(pattern);
  bridge.write_mem(0x100, value.value);
  require(device.captures == 1, "bulk write not captured");
  bridge.zero_out_dram();
  require(device.events[1] == "flush", "zeroing did not flush first");
  device.flush_dram_writes();
  require(device.memory.empty(), "pending write resurrected after zeroing");
}

void file(unsigned words, bool bulk) {
  BulkDevice fast(words);
  WidgetDevice legacy(words);
  WidgetDevice &device = bulk ? static_cast<WidgetDevice &>(fast) : legacy;
  auto bridge = loader(device);
  std::string line;
  for (unsigned i = 0; i < words * 16; ++i)
    line += "0123456789abcdef"[i % 16];
  {
    std::ofstream output("memory.hex");
    output << line << '\n' << line << '\n';
  }
  bridge.load_mem_from_file("memory.hex");
  auto expected = bytes(line, words * 8);
  device.expect(0, expected);
  device.expect(expected.size(), expected);
  if (bulk)
    require(fast.captures == 4 && device.dataWrites == 0 &&
                device.events.back() == "flush",
            "file load did not complete through bulk transport");
}

void zero_length(unsigned words) {
  BulkDevice device(words);
  auto bridge = loader(device);
  Integer value(pattern);
  bridge.write_mem_chunk(0x100, value.value, 0);
  require(device.events.empty() && device.captures == 0,
          "zero byte write had effects");
}
} // namespace

int main(int argc, char **argv) {
  try {
    require(argc == 2, "expected scenario");
    std::string scenario = argv[1];
    unsigned words = scenario.ends_with("-64") ? 2 : 16;
    std::string name = scenario.substr(0, scenario.rfind('-'));
    if (name == "legacy-single")
      single(words, false);
    else if (name == "legacy-chunk")
      chunks(words, false);
    else if (name == "bulk-single")
      single(words, true);
    else if (name == "bulk-chunk")
      chunks(words, true);
    else if (name == "declined")
      declined(words);
    else if (name == "flush-read")
      flush_read(words);
    else if (name == "mixed")
      mixed_transport(words);
    else if (name == "flush-zero")
      flush_zero(words);
    else if (name == "legacy-file")
      file(words, false);
    else if (name == "bulk-file")
      file(words, true);
    else if (name == "zero-length")
      zero_length(words);
    else
      throw std::runtime_error("unknown scenario");
    std::printf("%s: PASS (register-level transport test, no hardware)\n",
                scenario.c_str());
    return 0;
  } catch (const std::exception &error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
  }
}
