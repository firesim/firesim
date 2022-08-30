#include "fpga_managed_stream.h"

#include <assert.h>
#include <cstring>
#include <iostream>

void FPGAManagedStreams::FPGAToCPUDriver::init() {
  this->mmio_write(this->params.toHostPhysAddrHighAddr,
                   (uint32_t)(this->buffer_base_fpga >> 32));
  this->mmio_write(this->params.toHostPhysAddrLowAddr,
                   (uint32_t)this->buffer_base_fpga);
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
  size_t bytes_in_buffer = this->mmio_read(this->params.bytesAvailableAddr);
  if (bytes_in_buffer < required_bytes) {
    return 0;
  }

  void *src_addr = (char *)buffer_base + buffer_offset;
  size_t first_copy_bytes =
      ((buffer_offset + bytes_in_buffer) > this->params.buffer_capacity)
          ? this->params.buffer_capacity - buffer_offset
          : bytes_in_buffer;
  std::memcpy(dest, src_addr, first_copy_bytes);
  if (first_copy_bytes < bytes_in_buffer) {
    std::memcpy((char *)dest + first_copy_bytes,
                buffer_base,
                bytes_in_buffer - first_copy_bytes);
  }
  buffer_offset =
      (buffer_offset + bytes_in_buffer) % this->params.buffer_capacity;
  this->mmio_write(this->params.bytesConsumedAddr, bytes_in_buffer);
  return bytes_in_buffer;
}

void FPGAManagedStreams::FPGAToCPUDriver::flush() {
  this->mmio_write(this->params.toHostStreamFlushAddr, 1);
  // TODO: Consider if this should be made non-blocking // alternate API
  auto flush_done = false;
  int attempts = 0;
  while (!flush_done) {
    flush_done = (this->mmio_read(this->params.toHostStreamFlushDoneAddr) & 1);
    assert(++attempts < 256); // Bridge stream flush appears to deadlock
  }
}