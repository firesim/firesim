#include "fpga_managed_stream.h"

#include <assert.h>
#include <cstring>
#include <iostream>

void FPGAManagedStreams::FPGAToCPUDriver::init() {
  mmio_write(params.toHostPhysAddrHighAddr, (uint32_t)(buffer_base_fpga >> 32));
  mmio_write(params.toHostPhysAddrLowAddr, (uint32_t)buffer_base_fpga);
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

  void *src_addr = (char *)buffer_base + buffer_offset;
  size_t first_copy_bytes =
      ((buffer_offset + bytes_in_buffer) > params.buffer_capacity)
          ? params.buffer_capacity - buffer_offset
          : bytes_in_buffer;
  std::memcpy(dest, src_addr, first_copy_bytes);
  if (first_copy_bytes < bytes_in_buffer) {
    std::memcpy((char *)dest + first_copy_bytes,
                buffer_base,
                bytes_in_buffer - first_copy_bytes);
  }
  buffer_offset = (buffer_offset + bytes_in_buffer) % params.buffer_capacity;
  mmio_write(params.bytesConsumedAddr, bytes_in_buffer);
  return bytes_in_buffer;
}

void FPGAManagedStreams::FPGAToCPUDriver::flush() {
  mmio_write(params.toHostStreamFlushAddr, 1);
  // TODO: Consider if this should be made non-blocking // alternate API
  auto flush_done = false;
  int attempts = 0;
  while (!flush_done) {
    flush_done = (mmio_read(params.toHostStreamFlushDoneAddr) & 1);
    if (++attempts > 256) {
      exit(1); // Bridge stream flush appears to deadlock
    };
  }
}

FPGAManagedStreamWidget::FPGAManagedStreamWidget(
    FPGAManagedStreamIO &io,
    std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu) {

  char *fpga_address_memory_base = io.get_memory_base();
  uint64_t offset = 0;
  for (auto &&params : to_cpu) {
    uint32_t capacity = params.buffer_capacity;
    fpga_to_cpu_streams.push_back(
        std::make_unique<FPGAManagedStreams::FPGAToCPUDriver>(
            std::move(params),
            (void *)(fpga_address_memory_base + offset),
            offset,
            io));
    offset += capacity;
  }
}
