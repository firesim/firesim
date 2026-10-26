// See LICENSE for license details.
// Test double only: sparse predicted card memory, no SDK or hardware access.
#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

namespace vrt {
constexpr uint64_t base = 0x60000000000ull;
inline std::map<uint64_t, uint8_t> predicted_card;
inline std::vector<std::tuple<char, uint64_t, size_t>> transfers;
inline std::vector<std::tuple<char, size_t, uint32_t>> registers;
inline size_t allocations = 0, active = 0, allocation_attempts = 0;
inline size_t device_opens = 0;
// One-shot fault injection. Track complete allocator release: in the real SDK
// Allocator::deallocate also reclaims empty buddy superblocks.
inline size_t fail_allocation = 0, misplace_allocation = 0;
inline bool fail_with_null = false;
inline uint64_t misplaced_address = 0;
inline uint64_t next_address = base;
enum class BufferAllocType { Ddr };
enum class BufferAllocDir { Bidirectional };
enum class HBMRegion { NON_HBM };

class UntypedBuffer {
  uint64_t address;
  std::vector<uint8_t> host;
  void check(size_t offset, size_t bytes) {
    if (offset > host.size() || bytes > host.size() - offset)
      throw std::out_of_range("model DMA exceeds allocation");
  }

public:
  UntypedBuffer(uint64_t address, size_t bytes)
      : address(address), host(bytes) {}
  uint64_t getPhysAddr() const { return address; }
  size_t getSize() const { return host.size(); }
  void *data() { return host.data(); }
  void syncToDevice(size_t offset, size_t bytes) {
    check(offset, bytes);
    transfers.emplace_back('W', address + offset, bytes);
    for (size_t i = 0; i < bytes; ++i)
      predicted_card[address + offset + i] = host[offset + i];
  }
  void syncToHost(size_t offset, size_t bytes) {
    check(offset, bytes);
    transfers.emplace_back('R', address + offset, bytes);
    for (size_t i = 0; i < bytes; ++i)
      host[offset + i] = predicted_card[address + offset + i];
  }
};
class Block {
  UntypedBuffer view;

public:
  Block(uint64_t address, size_t bytes) : view(address, bytes) {}
  UntypedBuffer *getUntypedBuffer() { return &view; }
};
template <class T>
class Buffer {};
class Allocator {
public:
  std::unique_ptr<Block>
  allocate(int, BufferAllocType, BufferAllocDir, size_t bytes, HBMRegion) {
    ++allocation_attempts;
    if (allocation_attempts == fail_allocation) {
      if (fail_with_null)
        return nullptr;
      throw std::bad_alloc();
    }
    if (!bytes || bytes > 512ull * 1024 * 1024)
      throw std::runtime_error("model allocation exceeds per-region limit");
    size_t rounded = 4096;
    while (rounded < bytes)
      rounded *= 2;
    const auto address = allocation_attempts == misplace_allocation
                             ? misplaced_address
                             : next_address;
    auto result = std::make_unique<Block>(address, rounded);
    next_address += rounded;
    ++active;
    ++allocations;
    return result;
  }
  void deallocate(std::unique_ptr<Block> block) {
    if (block)
      --active;
    block.reset();
    if (!active)
      next_address = base;
  }
};
class Handle {
  Allocator allocator;

public:
  Allocator *getAllocator() { return &allocator; }
  int getVrtdDevice() { return 0; }
};
class Kernel {
public:
  void write(size_t address, uint32_t data) {
    if (address < 0x1000 || address >= 0x2000)
      throw std::runtime_error("model control access used an unsafe alias");
    registers.emplace_back('W', address, data);
  }
  uint32_t read(size_t address) {
    if (address < 0x1000 || address >= 0x2000)
      throw std::runtime_error("model control access used an unsafe alias");
    registers.emplace_back('R', address, 0x46697265);
#ifdef V80_DDR_STREAM_METADATA
    // Synthetic command completion only: no RTL DMA engine is simulated.
    if (address == 0x1000 + V80_DDR_STREAM_STATUS)
      return 2;
#endif
    return 0x46697265;
  }
};
class Device {
  std::shared_ptr<Handle> handle = std::make_shared<Handle>();

public:
  Device(const std::string &, const std::string &, bool program) {
    ++device_opens;
    if (program)
      throw std::runtime_error("CPU test requires use-loaded-image");
  }
  Kernel getKernel(const char *) { return {}; }
  std::shared_ptr<Handle> getHandle() { return handle; }
  void cleanup() {}
};
} // namespace vrt
