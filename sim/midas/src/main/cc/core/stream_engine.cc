#include "stream_engine.h"

#include <cassert>

void StreamEngine::init() {
  for (auto &stream : this->fpga_to_cpu_streams) {
    stream->init();
  }
  for (auto &stream : this->cpu_to_fpga_streams) {
    stream->init();
  }
}

size_t StreamEngine::pull(unsigned stream_idx,
                          void *dest,
                          size_t num_bytes,
                          size_t threshold_bytes) {
  assert(stream_idx < fpga_to_cpu_streams.size());
  return this->fpga_to_cpu_streams[stream_idx]->pull(
      dest, num_bytes, threshold_bytes);
}

size_t StreamEngine::push(unsigned stream_idx,
                          void *src,
                          size_t num_bytes,
                          size_t threshold_bytes) {
  assert(stream_idx < cpu_to_fpga_streams.size());
  return this->cpu_to_fpga_streams[stream_idx]->push(
      src, num_bytes, threshold_bytes);
}

void StreamEngine::pull_flush(unsigned stream_idx) {
  assert(stream_idx < fpga_to_cpu_streams.size());
  return this->fpga_to_cpu_streams[stream_idx]->flush();
}

void StreamEngine::push_flush(unsigned stream_idx) {
  assert(stream_idx < cpu_to_fpga_streams.size());
  return this->cpu_to_fpga_streams[stream_idx]->flush();
}
