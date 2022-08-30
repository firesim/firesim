#ifndef __BRIDGES_FPGA_MANAGED_STREAM_H
#define __BRIDGES_FPGA_MANAGED_STREAM_H

// See LICENSE for license details.

#include <functional>
#include <string>

#include "bridge_stream_driver.h"

namespace FPGAManagedStreams {
/**
 * @brief Parameters emitted for a FPGA-managed stream emitted by Golden Gate.
 *
 * This will be replaced by a protobuf-derived class, and re-used across both
 * Scala and C++.
 */
typedef struct StreamParameters {
  std::string stream_name;
  uint32_t buffer_capacity;
  uint64_t toHostPhysAddrHighAddr;
  uint64_t toHostPhysAddrLowAddr;
  uint64_t bytesAvailableAddr;
  uint64_t bytesConsumedAddr;
  uint64_t toHostStreamDoneInitAddr;
  uint64_t toHostStreamFlushAddr;
  uint64_t toHostStreamFlushDoneAddr;

  StreamParameters(std::string stream_name,
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
        toHostStreamFlushDoneAddr(toHostStreamFlushDoneAddr){};
} StreamParameters;

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
  FPGAToCPUDriver(StreamParameters params,
                  void *buffer_base,
                  uint64_t buffer_base_fpga,
                  std::function<uint32_t(size_t)> mmio_read,
                  std::function<void(size_t, uint32_t)> mmio_write)
      : params(params), buffer_base(buffer_base),
        buffer_base_fpga(buffer_base_fpga), mmio_read_func(mmio_read),
        mmio_write_func(mmio_write){};

  virtual size_t
  pull(void *dest, size_t num_bytes, size_t required_bytes) override;
  virtual void flush() override;
  virtual void init() override;

  size_t mmio_read(size_t addr) { return mmio_read_func(addr); };
  void mmio_write(size_t addr, uint32_t data) { mmio_write_func(addr, data); };

private:
  StreamParameters params;
  void *buffer_base;
  uint64_t buffer_base_fpga;
  std::function<uint32_t(size_t)> mmio_read_func;
  std::function<void(size_t, uint32_t)> mmio_write_func;

  // A read pointer offset from the base, in bytes
  int buffer_offset = 0;
};

} // namespace FPGAManagedStreams

#endif // __BRIDGES_FPGA_MANAGED_STREAM_H