#ifndef __BRIDGES_FPGA_MANAGED_STREAM_H
#define __BRIDGES_FPGA_MANAGED_STREAM_H

// See LICENSE for license details.

#include <functional>
#include <string>

#include "core/stream_engine.h"
#include "cpu_managed_stream.h"

class simif_t;

namespace FPGAManagedStreams {

/** A region of host memory that an FPGA-managed stream writes into. */
struct HostBuffer {
  /** Where this driver reads the data. */
  void *cpu;
  /**
   * The address the FPGA writes to.
   *
   * This is not generally `cpu`: the FPGA masters real bus transactions, so it
   * needs a physical (or device-visible) address, whereas the driver holds a
   * virtual one.
   */
  uint64_t fpga;
};

} // namespace FPGAManagedStreams

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
   * Reserve `size` bytes of host memory that the FPGA can write into, and
   * report both addresses for it.
   *
   * Called once per FPGA-to-CPU stream. Each stream gets its own region rather
   * than a slice of a shared one, because the physical contiguity a DMA engine
   * requires is only guaranteed *within* an allocation -- on F2 that means one
   * hugepage per stream, and slicing a single page would cap the total across
   * all streams at the page size.
   *
   * The returned region must remain mapped for the lifetime of this object.
   */
  virtual FPGAManagedStreams::HostBuffer allocate_to_cpu_buffer(size_t size) = 0;
};

namespace FPGAManagedStreams {

/** Mirrors midas.core.FPGAManagedStreamTarget; see that file for the rationale. */
enum class Target {
  /** Circular buffers in host DRAM, drained by this driver. */
  HostMemory,
  /** A peer FPGA's BAR (FireAxe partitioning); no driver-side consumer. */
  PeerFPGA,
};

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
      simif_t &simif,
      unsigned index,
      const std::vector<std::string> &args,
      FPGAManagedStreams::Target target,
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu);

private:
  /** Streams land in host DRAM; the driver polls and drains them. */
  void init_host_memory_streams(
      FPGAManagedStreamIO &io,
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu);

  /** Streams land in a peer FPGA's BAR4; addresses come from sysfs + plusargs. */
  void init_peer_fpga_streams(
      FPGAManagedStreamIO &io,
      const std::vector<std::string> &args,
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu);

  uint64_t get_p2p_bar_address(const char *dir_name);
};

class BiDirectionalManagedStreamIO : public FPGAManagedStreamIO,
                                     public CPUManagedStreamIO {};

#endif // __BRIDGES_FPGA_MANAGED_STREAM_H
