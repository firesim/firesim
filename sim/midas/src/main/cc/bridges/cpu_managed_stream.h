// See LICENSE for license details.

#ifndef __BRIDGES_CPU_MANAGED_STREAM_H
#define __BRIDGES_CPU_MANAGED_STREAM_H

#include <functional>
#include <string>

#include "bridge_stream_driver.h"

namespace CPUManagedStreams {
/**
 * @brief Parameters emitted for a CPU-managed stream emitted by Golden Gate.
 *
 * This will be replaced by a protobuf-derived class, and re-used across both
 * Scala and C++.
 */
typedef struct StreamParameters {
  std::string stream_name;
  uint64_t dma_addr;
  uint64_t count_addr;
  uint32_t fpga_buffer_size;

  StreamParameters(std::string stream_name,
                   uint64_t dma_addr,
                   uint64_t count_addr,
                   int fpga_buffer_size)
      : stream_name(stream_name), dma_addr(dma_addr), count_addr(count_addr),
        fpga_buffer_size(fpga_buffer_size){};
} StreamParameters;

/**
 * @brief Base class for CPU-managed streams
 *
 * Streams implemented with the CPUManagedStreamEngine have a common set of
 * parameters, and use MMIO to measure FPGA-queue occupancy. This base class
 * captures that.
 *
 * Children of this class implement the host-independent control for streams.
 * Generally, this consists of doing an MMIO read to FPGA-side queue capacity,
 * to determine if a stream request can be served. Host implementations
 * instantiate these classes with callbacks to implement MMIO and either CPU- or
 * FPGA-managed AXI4 for their platform.
 *
 */
class CPUManagedDriver {
public:
  CPUManagedDriver(StreamParameters params,
                   std::function<uint32_t(size_t)> mmio_read_func)
      : params(params), mmio_read_func(mmio_read_func){};
  virtual ~CPUManagedDriver(){};

private:
  StreamParameters params;
  std::function<uint32_t(size_t)> mmio_read_func;

public:
  size_t mmio_read(size_t addr) { return mmio_read_func(addr); };
  // Accessors to avoid directly operating on params
  int fpga_buffer_size() { return params.fpga_buffer_size; };
  uint64_t dma_addr() { return params.dma_addr; };
  uint64_t count_addr() { return params.count_addr; };
};

/**
 * @brief Implements streams sunk by the driver (sourced by the FPGA)
 *
 * Extends CPUManagedStream to provide a pull method, which moves data from the
 * FPGA into a user-provided buffer. IO over a CPU-managed AXI4 IF is
 * implemented with axi4_read, and is provided by the host-platform.
 *
 */
class FPGAToCPUDriver final : public CPUManagedDriver,
                              public FPGAToCPUStreamDriver {
public:
  FPGAToCPUDriver(StreamParameters params,
                  std::function<uint32_t(size_t)> mmio_read,
                  std::function<size_t(size_t, char *, size_t)> axi4_read)
      : CPUManagedDriver(params, mmio_read), axi4_read(axi4_read){};

  virtual size_t
  pull(void *dest, size_t num_bytes, size_t required_bytes) override;
  // The CPU-managed stream engine makes all beats available to the bridge,
  // hence the NOP.
  virtual void flush() override{};
  virtual void init() override{};

private:
  std::function<size_t(size_t, char *, size_t)> axi4_read;
};

/**
 * @brief Implements streams sourced by the driver (sunk by the FPGA)
 *
 * Extends CPUManagedStream to provide a push method, which moves data to the
 * FPGA out of a user-provided buffer. IO over a CPU-managed AXI4 IF is
 * implemented with axi4_write, and is provided by the host-platform.
 */
class CPUToFPGADriver final : public CPUManagedDriver,
                              public CPUToFPGAStreamDriver {
public:
  CPUToFPGADriver(StreamParameters params,
                  std::function<uint32_t(size_t)> mmio_read,
                  std::function<size_t(size_t, char *, size_t)> axi4_write)
      : CPUManagedDriver(params, mmio_read), axi4_write(axi4_write){};

  virtual size_t
  push(void *src, size_t num_bytes, size_t required_bytes) override;
  // On a push all beats are delivered to the FPGA, so a NOP is sufficient here.
  virtual void flush() override{};
  virtual void init() override{};

private:
  std::function<size_t(size_t, char *, size_t)> axi4_write;
};

} // namespace CPUManagedStreams

#endif // __BRIDGES_CPU_MANAGED_STREAM_H
