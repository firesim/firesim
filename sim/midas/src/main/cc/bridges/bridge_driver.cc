#include "bridge_driver.h"

size_t streaming_bridge_driver_t::pull(unsigned stream_idx,
                                       void *data,
                                       size_t size,
                                       size_t minimum_batch_size) {
  return stream.pull(stream_idx, data, size, minimum_batch_size);
}

size_t streaming_bridge_driver_t::push(unsigned stream_idx,
                                       void *data,
                                       size_t size,
                                       size_t minimum_batch_size) {
  return stream.push(stream_idx, data, size, minimum_batch_size);
}

void streaming_bridge_driver_t::pull_flush(unsigned stream_idx) {
  return stream.pull_flush(stream_idx);
}
