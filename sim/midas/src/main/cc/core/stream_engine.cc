#include "stream_engine.h"

#include <cassert>
#include <stdio.h>

void HostStreamEngine::init() {
  for (auto &stream : this->fpga_to_cpu_streams) {
    stream->init();
  }
  for (auto &stream : this->cpu_to_fpga_streams) {
    stream->init();
  }
}

size_t HostStreamEngine::pull(unsigned stream,
                              void *dest,
                              size_t num_bytes,
                              size_t required_bytes) {
  assert(stream < fpga_to_cpu_streams.size());
  return this->fpga_to_cpu_streams[stream]->pull(
      dest, num_bytes, required_bytes);
}

size_t HostStreamEngine::push(unsigned stream,
                              void *src,
                              size_t num_bytes,
                              size_t required_bytes) {
  assert(stream < cpu_to_fpga_streams.size());
  return this->cpu_to_fpga_streams[stream]->push(
      src, num_bytes, required_bytes);
}

void HostStreamEngine::pull_flush(unsigned stream) {
  assert(stream < fpga_to_cpu_streams.size());
  return this->fpga_to_cpu_streams[stream]->flush();
}

void HostStreamEngine::push_flush(unsigned stream) {
  assert(stream < cpu_to_fpga_streams.size());
  return this->cpu_to_fpga_streams[stream]->flush();
}
