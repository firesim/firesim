#ifndef __BRIDGES_FPGA_MANAGED_STREAM_H
#define __BRIDGES_FPGA_MANAGED_STREAM_H

// See LICENSE for license details.

#include <functional>
#include <string>

#include "core/stream_engine.h"

/**
 * An abstraction over the low-level hardware interface on which streams rely.
 *
 * The hardware interface must implement these methods to provide access to
 * the memory region where the AXI interface used by the streams is mapped to,
 * as well as the controller's MMIO mechanism.
 */
class FPGAManagedStreamIO {
public:
  /**
   * Reads from the MMIO control interface.
   */
  virtual uint32_t mmio_read(size_t addr) = 0;

  /**
   * Performs a write to the MMIO control interface.
   */
  virtual void mmio_write(size_t addr, uint32_t value) = 0;

  /**
   * Returns a pointer to the memory region where the device is mapped.
   */
  virtual char *get_memory_base() = 0;
};

namespace FPGAManagedStreams {
/**
 * @brief Parameters emitted for a FPGA-managed stream emitted by Golden Gate.
 *
 * This will be replaced by a protobuf-derived class, and re-used across both
 * Scala and C++.
 */
struct StreamParameters {
  std::string stream_name;
  uint32_t buffer_capacity;
  uint64_t toHostPhysAddrHighAddr;
  uint64_t toHostPhysAddrLowAddr;
  uint64_t bytesAvailableAddr;
  uint64_t bytesConsumedAddr;
  uint64_t toHostStreamDoneInitAddr;
  uint64_t toHostStreamFlushAddr;
  uint64_t toHostStreamFlushDoneAddr;

  StreamParameters(const std::string &stream_name,
                   uint32_t buffer_capacity,
                   uint64_t toHostPhysAddrHighAddr,
                   uint64_t toHostPhysAddrLowAddr,
                   uint64_t bytesAvailableAddr,
                   uint64_t bytesConsumedAddr,
                   uint64_t toHostStreamDoneInitAddr,
                   uint64_t toHostStreamFlushAddr,
                   uint64_t toHostStreamFlushDoneAddr)
      : stream_name(stream_name), buffer_capacity(buffer_capacity),
        toHostPhysAddrHighAddr(toHostPhysAddrHighAddr),
        toHostPhysAddrLowAddr(toHostPhysAddrLowAddr),
        bytesAvailableAddr(bytesAvailableAddr),
        bytesConsumedAddr(bytesConsumedAddr),
        toHostStreamDoneInitAddr(toHostStreamDoneInitAddr),
        toHostStreamFlushAddr(toHostStreamFlushAddr),
        toHostStreamFlushDoneAddr(toHostStreamFlushDoneAddr) {}
};

/**
 * @brief Implements streams sunk by the driver (sourced by the FPGA)
 *
 * Extends FPGAManagedStream to provide a pull method, which moves data from the
 * FPGA into a user-provided buffer. IO over a FPGA-mastered AXI4 IF is
 * implemented with pcis_read, and is provided by the host-platform.
 *
 */
class FPGAToCPUDriver : public FPGAToCPUStreamDriver {
public:
  FPGAToCPUDriver(StreamParameters &&params,
                  void *buffer_base,
                  uint64_t buffer_base_fpga,
                  FPGAManagedStreamIO &io)
      : params(std::move(params)), buffer_base(buffer_base),
        buffer_base_fpga(buffer_base_fpga), io(io) {}

  size_t pull(void *dest, size_t num_bytes, size_t required_bytes) override;
  void flush() override;
  void init() override;

  size_t mmio_read(size_t addr) { return io.mmio_read(addr); };
  void mmio_write(size_t addr, uint32_t data) { io.mmio_write(addr, data); };

private:
  StreamParameters params;
  void *buffer_base;
  uint64_t buffer_base_fpga;
  FPGAManagedStreamIO &io;

  // A read pointer offset from the base, in bytes
  int buffer_offset = 0;
};

} // namespace FPGAManagedStreams

/**
 * Widget handling FPGA-managed streams.
 */
class FPGAManagedStreamWidget final : public StreamEngine {
public:
  /**
   * Creates a new FPGA-managed stream widget.
   *
   * @param io Reference to a functor implementing the low-level IO.
   */
  FPGAManagedStreamWidget(
      FPGAManagedStreamIO &io,
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu);
};

#endif // __BRIDGES_FPGA_MANAGED_STREAM_H
