// See LICENSE for license details.

#ifndef __BRIDGES_CPU_MANAGED_STREAM_H
#define __BRIDGES_CPU_MANAGED_STREAM_H

#include <functional>
#include <string>

#include "core/stream_engine.h"

class simif_t;

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
  uint32_t fpga_buffer_width_bytes;
  size_t a0; size_t a1; size_t a2; size_t a3; size_t a4;
  size_t a5; size_t a6; size_t a7; size_t a8; size_t a9;

  StreamParameters(const std::string &stream_name,
                   uint64_t dma_addr,
                   uint64_t count_addr,
                   int fpga_buffer_size,
                   int fpga_buffer_width_bytes,
                   size_t a0, size_t a1, size_t a2, size_t a3, size_t a4,
                   size_t a5, size_t a6, size_t a7, size_t a8, size_t a9
                   )
      : stream_name(stream_name), dma_addr(dma_addr), count_addr(count_addr),
        fpga_buffer_size(fpga_buffer_size),
        fpga_buffer_width_bytes(fpga_buffer_width_bytes),
        a0(a0),  
        a1(a1),  
        a2(a2),  
        a3(a3),  
        a4(a4),  
        a5(a5),  
        a6(a6),  
        a7(a7),  
        a8(a8),  
        a9(a9)
    {}
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
  const StreamParameters params;
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
  uint64_t fpga_buffer_width_bytes() const { return params.fpga_buffer_width_bytes; }

  uint64_t a0() { return params.a0; };
  uint64_t a1() { return params.a1; };
  uint64_t a2() { return params.a2; };
  uint64_t a3() { return params.a3; };
  uint64_t a4() { return params.a4; };
  uint64_t a5() { return params.a5; };
  uint64_t a6() { return params.a6; };
  uint64_t a7() { return params.a7; };
  uint64_t a8() { return params.a8; };
  uint64_t a9() { return params.a9; };

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
      simif_t &simif,
      unsigned index,
      const std::vector<std::string> &args,
      std::vector<CPUManagedStreams::StreamParameters> &&from_cpu,
      std::vector<CPUManagedStreams::StreamParameters> &&to_cpu);
};

#endif // __BRIDGES_CPU_MANAGED_STREAM_H
