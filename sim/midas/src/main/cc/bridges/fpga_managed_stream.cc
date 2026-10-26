#include "fpga_managed_stream.h"
#include "core/simif.h"

#include <algorithm>
#include <assert.h>
#include <cstring>
#include <inttypes.h>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <stdio.h>
#include <string>
#include <vector>

FPGAManagedStreams::FPGAToCPUDriver::FPGAToCPUDriver(
    StreamParameters &&params,
    void *buffer_base,
    uint64_t buffer_base_fpga,
    FPGAManagedStreamIO &io,
    std::chrono::milliseconds timeout)
    : timeout(timeout), params(std::move(params)), buffer_base(buffer_base),
      buffer_base_fpga(buffer_base_fpga), io(io) {
  if (timeout.count() <= 0)
    throw std::invalid_argument(
        "FPGA-managed output requires a positive timeout");
}

void FPGAManagedStreams::FPGAToCPUDriver::init() {
  fprintf(stdout, "PCIM Peer Base Addr: 0x%" PRIx64 "\n", buffer_base_fpga);
  mmio_write(params.toHostPhysAddrHighAddr, (uint32_t)(buffer_base_fpga >> 32));
  mmio_write(params.toHostPhysAddrLowAddr, (uint32_t)buffer_base_fpga);
  mmio_write(params.toHostStreamDoneInitAddr, 1);
}
/**
 * @brief Dequeues as much as num_bytes of data from the associated bridge
 * stream.
 *
 * @param dest  Buffer into which to copy dequeued stream data
 * @param num_bytes  Bytes of data to dequeue
 * @param required_bytes  Minimum number of bytes to dequeue. If fewer bytes
 * would be dequeued, dequeue none and return 0.
 * @return size_t Number of bytes successfully dequeued
 */
size_t FPGAManagedStreams::FPGAToCPUDriver::pull(void *dest,
                                                 size_t num_bytes,
                                                 size_t required_bytes) {
  assert(num_bytes >= required_bytes);
  size_t bytes_in_buffer = mmio_read(params.bytesAvailableAddr);
  if (bytes_in_buffer < required_bytes) {
    return 0;
  }

  // The FPGA may have queued more than the caller asked for. Copy only what
  // fits in dest; the remainder stays queued for the next pull. This mirrors
  // the clamp in CPUManagedStreams::FPGAToCPUDriver::pull.
  bytes_in_buffer = std::min(bytes_in_buffer, num_bytes);

  void *src_addr = (char *)buffer_base + buffer_offset;
  size_t first_copy_bytes =
      ((buffer_offset + bytes_in_buffer) > params.buffer_capacity)
          ? params.buffer_capacity - buffer_offset
          : bytes_in_buffer;
  if (first_copy_bytes)
    io.sync_from_device(static_cast<char *>(src_addr), first_copy_bytes);
  std::memcpy(dest, src_addr, first_copy_bytes);
  if (first_copy_bytes < bytes_in_buffer) {
    io.sync_from_device(static_cast<char *>(buffer_base),
                        bytes_in_buffer - first_copy_bytes);
    std::memcpy((char *)dest + first_copy_bytes,
                buffer_base,
                bytes_in_buffer - first_copy_bytes);
  }
  buffer_offset = (buffer_offset + bytes_in_buffer) % params.buffer_capacity;
  mmio_write(params.bytesConsumedAddr, bytes_in_buffer);
  return bytes_in_buffer;
}

void FPGAManagedStreams::FPGAToCPUDriver::flush() {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  mmio_write(params.toHostStreamFlushAddr, 1);
  // The number of control reads needed to drain a burst depends on memory
  // width and backpressure. A fixed poll count can reject a progressing DMA.
  while (!(mmio_read(params.toHostStreamFlushDoneAddr) & 1)) {
    if (std::chrono::steady_clock::now() >= deadline)
      throw std::runtime_error("FPGA-managed output stream flush timed out");
  }
}

FPGAManagedStreamWidget::FPGAManagedStreamWidget(
    simif_t &simif,
    unsigned index,
    const std::vector<std::string> &args,
    FPGAManagedStreams::Target target,
    std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu) {
  assert(index == 0 && "only one managed stream engine is allowed");

  auto &io = simif.get_fpga_managed_stream_io();

  switch (target) {
  case FPGAManagedStreams::Target::HostMemory:
    init_host_memory_streams(io, std::move(to_cpu));
    break;
  case FPGAManagedStreams::Target::PeerFPGA:
    init_peer_fpga_streams(io, args, std::move(to_cpu));
    break;
  }
}

/** Give each stream its own host-memory region; see allocate_to_cpu_buffer. */
void FPGAManagedStreamWidget::init_host_memory_streams(
    FPGAManagedStreamIO &io,
    std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu) {
  for (auto &&params : to_cpu) {
    auto buffer = io.allocate_to_cpu_buffer(params.buffer_capacity);
    fpga_to_cpu_streams.push_back(
        std::make_unique<FPGAManagedStreams::FPGAToCPUDriver>(
            std::move(params), buffer.cpu, buffer.fpga, io));
  }
}

void FPGAManagedStreamWidget::init_peer_fpga_streams(
    FPGAManagedStreamIO &io,
    const std::vector<std::string> &args,
    std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu) {
  int idx = 0;
  bool found = false;
  const char *resource_name[8] = {};

  // TODO : Currently using the number of FPGAs in the partition as a proxy of
  // the AWS instance type Need a better way of doing this...
  int f1_instance_fpga_cnt = 2;
  std::string fpga_cnt_arg = std::string("+partition-fpga-cnt=");
  for (auto &arg : args) {
    if (arg.find(fpga_cnt_arg) == 0) {
      char *str = const_cast<char *>(arg.c_str()) + fpga_cnt_arg.length();
      int fpga_cnt = atoi(str);
      f1_instance_fpga_cnt = fpga_cnt > 2 ? 8 : 2;
    }
  }
  /* printf("f1_instance_fpga_cnt: %d\n", f1_instance_fpga_cnt); */

  if (f1_instance_fpga_cnt == 2) {
    resource_name[0] = "0000:00:1b.0";
    resource_name[1] = "0000:00:1d.0";
  } else if (f1_instance_fpga_cnt == 8) {
    resource_name[0] = "0000:00:0f.0";
    resource_name[1] = "0000:00:11.0";
    resource_name[2] = "0000:00:13.0";
    resource_name[3] = "0000:00:15.0";
    resource_name[4] = "0000:00:17.0";
    resource_name[5] = "0000:00:19.0";
    resource_name[6] = "0000:00:1b.0";
    resource_name[7] = "0000:00:1d.0";
  }

  uint64_t p2p_bar4_addrs[f1_instance_fpga_cnt];
  for (int slotid = 0; slotid < f1_instance_fpga_cnt; slotid++) {
    p2p_bar4_addrs[slotid] = get_p2p_bar_address(resource_name[slotid]);
  }

  std::vector<uint64_t> pcis_offsets;
  do {
    std::string peer_pcis_offset_args = std::string("+peer-pcis-offset") +
                                        std::to_string(idx) + std::string("=");
    found = false;
    for (auto &arg : args) {
      if (arg.find(peer_pcis_offset_args) == 0) {
        found = true;
        char *c_str =
            const_cast<char *>(arg.c_str() + peer_pcis_offset_args.length());
        std::string cpp_str = c_str;
        std::stringstream ss(cpp_str);
        std::string token;
        std::vector<std::string> words;
        while (getline(ss, token, ',')) {
          words.push_back(token);
        }

        int slotid = std::stoi(words[0]);
        int bridge_offset = std::stoi(words[1]);
        uint64_t offset = p2p_bar4_addrs[slotid] + bridge_offset;
        printf("P2P neighbor slotid: %d, p2p_bar4_addrs: 0x%" PRIx64
               " bridge_offset: 0x%x offset: 0x%" PRIx64 "\n",
               slotid,
               p2p_bar4_addrs[slotid],
               bridge_offset,
               offset);

        pcis_offsets.push_back(offset);
      }
    }
    idx++;
  } while (found);

  idx = 0;
  for (auto &&params : to_cpu) {
    uint32_t capacity = params.buffer_capacity;
    uint64_t offset = pcis_offsets[idx];
    fpga_to_cpu_streams.push_back(
        std::make_unique<FPGAManagedStreams::FPGAToCPUDriver>(
            std::move(params), (void *)(offset), offset, io));
    idx++;
  }
}

uint64_t FPGAManagedStreamWidget::get_p2p_bar_address(const char *dir_name) {
  int ret;
  uint64_t physical_addr = 0;
  if (!dir_name) {
    printf("dir_name is null\n");
    assert(false);
  }

  char sysfs_name[256];
  ret = snprintf(sysfs_name,
                 sizeof(sysfs_name),
                 "/sys/bus/pci/devices/%s/resource",
                 dir_name);

  if (ret < 0) {
    printf("Error building the sysfs path for resource\n");
    assert(false);
  }
  if ((size_t)ret >= sizeof(sysfs_name)) {
    printf("sysfs path too long for resource\n");
    assert(false);
  }

  FILE *fp = fopen(sysfs_name, "r");
  if (!fp) {
    printf("Error opening %s\n", sysfs_name);
    assert(false);
  }

#ifndef FPGA_BAR_PER_PF_MAX
#define FPGA_BAR_PER_PF_MAX 5
#endif

  for (size_t i = 0; i < FPGA_BAR_PER_PF_MAX; ++i) {
    uint64_t addr_begin = 0, addr_end = 0, flags = 0;
    ret = fscanf(fp, "0x%lx 0x%lx 0x%lx\n", &addr_begin, &addr_end, &flags);
    if (ret < 3 || addr_begin == 0) {
      continue;
    }
    if (i == 4) {
      physical_addr = addr_begin;
    }
  }
  fclose(fp);

  printf("get_p2p_bar_address physical_addr: 0x%" PRIx64 "\n", physical_addr);
  assert(physical_addr != 0 && "Unable to get valid physical_addr");

  return physical_addr;
}

FPGAManagedStreams::CPUToFPGADriver::CPUToFPGADriver(
    FromCPUStreamParameters &&params,
    void *buffer_base,
    uint64_t buffer_base_fpga,
    FPGAManagedStreamIO &io,
    std::chrono::milliseconds timeout)
    : timeout(timeout), params(std::move(params)), buffer_base(buffer_base),
      buffer_base_fpga(buffer_base_fpga), io(io) {
  const auto size = this->params.buffer_capacity;
  if (!buffer_base || size < 64 || (size & (size - 1)) ||
      (buffer_base_fpga % 4096) || timeout.count() <= 0)
    throw std::invalid_argument("Invalid FPGA-managed input ring");
}

void FPGAManagedStreams::CPUToFPGADriver::check_ready() const {
  if (failed)
    throw std::runtime_error(
        "FPGA-managed input ring failed; recreate before reuse");
}

void FPGAManagedStreams::CPUToFPGADriver::wait_until(
    size_t address,
    const std::function<bool(uint32_t)> &ready,
    std::chrono::steady_clock::time_point deadline) {
  while (!ready(mmio_read(address))) {
    if (std::chrono::steady_clock::now() >= deadline) {
      failed = true;
      throw std::runtime_error("FPGA-managed input ring timed out");
    }
  }
}

void FPGAManagedStreams::CPUToFPGADriver::init() {
  check_ready();
  mmio_write(params.fromHostPhysAddrHighAddr, buffer_base_fpga >> 32);
  mmio_write(params.fromHostPhysAddrLowAddr, buffer_base_fpga);
  mmio_write(params.fromHostStreamDoneInitAddr, 1);
}

size_t FPGAManagedStreams::CPUToFPGADriver::push(void *src,
                                                 size_t num_bytes,
                                                 size_t required_bytes) {
  check_ready();
  if (num_bytes < required_bytes || num_bytes % 64 || required_bytes % 64 ||
      (num_bytes && !src))
    throw std::invalid_argument(
        "FPGA-managed input requires complete 64-byte tokens");
  if (!num_bytes)
    return 0;
  const uint32_t consumed = mmio_read(params.bytesConsumedAddr);
  const uint32_t in_flight = pushed_total - consumed;
  if (in_flight > params.buffer_capacity || in_flight % 64) {
    failed = true;
    throw std::runtime_error(
        "FPGA-managed input ring has invalid consumption credit");
  }
  const size_t available = params.buffer_capacity - in_flight;
  if (available < required_bytes || !available)
    return 0;
  const size_t count = std::min(num_bytes, available);
  const size_t first = std::min(count, params.buffer_capacity - write_offset);
  auto *ring = static_cast<char *>(buffer_base);
  std::memcpy(ring + write_offset, src, first);
  io.sync_to_device(ring + write_offset, first);
  if (count > first) {
    std::memcpy(ring, static_cast<char *>(src) + first, count - first);
    io.sync_to_device(ring, count - first);
  }
  write_offset = (write_offset + count) % params.buffer_capacity;
  pushed_total += count;
  try {
    mmio_write(params.bytesAvailableAddr, count);
    wait_until(
        params.bytesAvailableAddr,
        [](uint32_t v) { return v == 0; },
        std::chrono::steady_clock::now() + timeout);
  } catch (...) {
    failed = true;
    throw;
  }
  return count;
}

void FPGAManagedStreams::CPUToFPGADriver::flush() {
  check_ready();
  // One deadline covers all outstanding copies and the final bridge drain.
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  try {
    wait_until(
        params.bytesConsumedAddr,
        [this](uint32_t v) { return v == pushed_total; },
        deadline);
    mmio_write(params.fromHostStreamFlushAddr, 1);
    wait_until(
        params.fromHostStreamFlushDoneAddr,
        [](uint32_t v) { return v & 1; },
        deadline);
    mmio_write(params.fromHostStreamFlushAddr, 0);
  } catch (...) {
    failed = true;
    throw;
  }
}

FPGAManagedStreamWidget::FPGAManagedStreamWidget(
    simif_t &simif,
    unsigned index,
    const std::vector<std::string> &args,
    FPGAManagedStreams::Target target,
    std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu,
    std::vector<FPGAManagedStreams::FromCPUStreamParameters> &&from_cpu)
    : FPGAManagedStreamWidget(simif, index, args, target, std::move(to_cpu)) {
  if (target != FPGAManagedStreams::Target::HostMemory)
    throw std::invalid_argument(
        "FPGA-managed input streams require host-memory rings");
  auto &io = simif.get_fpga_managed_stream_io();
  for (auto &&params : from_cpu) {
    auto buffer = io.allocate_from_cpu_buffer(params.buffer_capacity);
    cpu_to_fpga_streams.push_back(
        std::make_unique<FPGAManagedStreams::CPUToFPGADriver>(
            std::move(params), buffer.cpu, buffer.fpga, io));
  }
}
