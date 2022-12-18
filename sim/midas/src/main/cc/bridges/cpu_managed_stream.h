// See LICENSE for license details.

#ifndef __BRIDGES_CPU_MANAGED_STREAM_H
#define __BRIDGES_CPU_MANAGED_STREAM_H

#include <functional>
#include <string>

#include "core/stream_engine.h"

/**
 * An abstraction over the low-level hardware interface on which streams rely.
 *
 * The hardware interface must implement these methods to provide access to
 * the CPU-managed AXI4 interface, as well as the controller's MMIO mechanism.
 */
class CPUManagedStreamIO {
public:
  virtual ~CPUManagedStreamIO() = default;

  /**
   * Reads from the MMIO control interface.
   */
  virtual uint32_t mmio_read(size_t addr) = 0;

  /**
   * Writes a buffer to the CPU-managed AXI interface.
   */
  virtual size_t
  cpu_managed_axi4_write(size_t addr, const char *data, size_t size) = 0;

  /**
   * Reads a buffer from the CPU-managed AXI interface.
   */
  virtual size_t
  cpu_managed_axi4_read(size_t addr, char *data, size_t size) = 0;

  /**
   * Returns the number of bytes to read in an axi4 transaction beat.
   *
   * More precisely, returns the width of the data field in bytes.
   */
  virtual uint64_t get_beat_bytes() const = 0;
};

namespace CPUManagedStreams {
/**
 * @brief Parameters emitted for a CPU-managed stream emitted by Golden Gate.
 *
 * This will be replaced by a protobuf-derived class, and re-used across both
 * Scala and C++.
 */
struct StreamParameters {
  std::string stream_name;
  uint64_t dma_addr;
  uint64_t count_addr;
  uint32_t fpga_buffer_size;

  StreamParameters(const std::string &stream_name,
                   uint64_t dma_addr,
                   uint64_t count_addr,
                   int fpga_buffer_size)
      : stream_name(stream_name), dma_addr(dma_addr), count_addr(count_addr),
        fpga_buffer_size(fpga_buffer_size) {}
};

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
  CPUManagedDriver(StreamParameters &&params, CPUManagedStreamIO &io)
      : params(std::move(params)), io(io) {}

  virtual ~CPUManagedDriver() = default;

private:
  StreamParameters params;
  CPUManagedStreamIO &io;

public:
  size_t mmio_read(size_t addr) { return io.mmio_read(addr); }

  size_t cpu_managed_axi4_write(size_t addr, const char *data, size_t size) {
    return io.cpu_managed_axi4_write(addr, data, size);
  }

  size_t cpu_managed_axi4_read(size_t addr, char *data, size_t size) {
    return io.cpu_managed_axi4_read(addr, data, size);
  }

  // Accessors to avoid directly operating on params
  int fpga_buffer_size() { return params.fpga_buffer_size; };
  uint64_t dma_addr() { return params.dma_addr; };
  uint64_t count_addr() { return params.count_addr; };
  uint64_t beat_bytes() const { return io.get_beat_bytes(); }
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
  FPGAToCPUDriver(StreamParameters &&params, CPUManagedStreamIO &io)
      : CPUManagedDriver(std::move(params), io) {}

  size_t pull(void *dest, size_t num_bytes, size_t required_bytes) override;
  // The CPU-managed stream engine makes all beats available to the bridge,
  // hence the NOP.
  void flush() override {}
  void init() override {}
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
  CPUToFPGADriver(StreamParameters &&params, CPUManagedStreamIO &io)
      : CPUManagedDriver(std::move(params), io) {}

  size_t push(void *src, size_t num_bytes, size_t required_bytes) override;
  // On a push all beats are delivered to the FPGA, so a NOP is sufficient here.
  void flush() override {}
  void init() override {}
};

} // namespace CPUManagedStreams

/**
 * Widget handling CPU-managed streams.
 */
class CPUManagedStreamWidget final : public StreamEngine {
public:
  /**
   * Creates a new CPU-managed stream widget.
   *
   * @param io Reference to a functor implementing low-level IO.
   */
  CPUManagedStreamWidget(
      CPUManagedStreamIO &io,
      std::vector<CPUManagedStreams::StreamParameters> &&from_cpu,
      std::vector<CPUManagedStreams::StreamParameters> &&to_cpu);
};

#endif // __BRIDGES_CPU_MANAGED_STREAM_H
