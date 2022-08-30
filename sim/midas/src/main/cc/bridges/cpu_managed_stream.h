// See LICENSE for license details.

#ifndef __CPU_MANAGED_STREAM_H
#define __CPU_MANAGED_STREAM_H

#include <functional>
#include <string>
/**
 * @brief Parameters emitted for a CPU-managed stream emitted by Golden Gate.
 *
 * This will be replaced by a protobuf-derived class, and re-used across both
 * Scala and C++.
 */
typedef struct CPUManagedStreamParameters {
  std::string stream_name;
  uint64_t dma_addr;
  uint64_t count_addr;
  uint32_t fpga_buffer_size;

  CPUManagedStreamParameters(std::string stream_name,
                             uint64_t dma_addr,
                             uint64_t count_addr,
                             int fpga_buffer_size)
      : stream_name(stream_name), dma_addr(dma_addr), count_addr(count_addr),
        fpga_buffer_size(fpga_buffer_size){};
} CPUManagedStreamParameters;

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
 * instantiate these classes with callbacks to implement MMIO and DMA/PCIS/PCIM
 * for their platform.
 *
 */
class CPUManagedStream {
public:
  CPUManagedStream(CPUManagedStreamParameters params,
                   std::function<uint32_t(size_t)> mmio_read_func)
      : params(params), mmio_read_func(mmio_read_func){};

private:
  CPUManagedStreamParameters params;
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
 * FPGA into a user-provided buffer. IO over a CPU-mastered AXI4 IF is
 * implemented with pcis_read, and is provided by the host-platform.
 *
 */
class StreamToCPU : public CPUManagedStream {
public:
  StreamToCPU(CPUManagedStreamParameters params,
              std::function<uint32_t(size_t)> mmio_read,
              std::function<size_t(size_t, char *, size_t)> pcis_read)
      : CPUManagedStream(params, mmio_read), pcis_read(pcis_read){};

  size_t pull(void *dest, size_t num_bytes, size_t required_bytes);

private:
  std::function<size_t(size_t, char *, size_t)> pcis_read;
};

/**
 * @brief Implements streams sourced by the driver (sunk by the FPGA)
 *
 * Extends CPUManagedStream to provide a push method, which moves data to the
 * FPGA out of a user-provided buffer. IO over a CPU-mastered AXI4 IF is
 * implemented with pcis_write, and is provided by the host-platform.
 */
class StreamFromCPU : public CPUManagedStream {
public:
  StreamFromCPU(CPUManagedStreamParameters params,
                std::function<uint32_t(size_t)> mmio_read,
                std::function<size_t(size_t, char *, size_t)> pcis_write)
      : CPUManagedStream(params, mmio_read), pcis_write(pcis_write){};

  size_t push(void *src, size_t num_bytes, size_t required_bytes);

private:
  std::function<size_t(size_t, char *, size_t)> pcis_write;
};

#endif // __CPU_MANAGED_STREAM_H
