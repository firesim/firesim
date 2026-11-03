#ifndef __BRIDGES_FPGA_MANAGED_STREAM_H
#define __BRIDGES_FPGA_MANAGED_STREAM_H

// See LICENSE for license details.

#include <functional>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <string>

#include "core/stream_engine.h"
#include "cpu_managed_stream.h"

class simif_t;

namespace FPGAManagedStreams {

/** A region of host memory accessible to the FPGA-managed stream engine. */
struct HostBuffer {
  /** The host's virtual address for reading or writing payload data. */
  void *cpu;
  /**
   * The address the FPGA reads from or writes to.
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
   * Reserve `size` bytes of host memory that the FPGA can access, and
   * report both addresses for it.
   *
   * Called once per host-memory stream. Each stream gets its own region rather
   * than a slice of a shared one, because the physical contiguity a DMA engine
   * requires is only guaranteed *within* an allocation -- on F2 that means one
   * hugepage per stream, and slicing a single page would cap the total across
   * all streams at the page size.
   *
   * The returned region must remain mapped for the lifetime of this object.
   */
  virtual FPGAManagedStreams::HostBuffer
  allocate_to_cpu_buffer(size_t size) = 0;

  /** Submit one 32-bit word of an H2C descriptor through BAR4/PCIS. */
  virtual void pcis_write32(uint64_t, uint32_t) {
    throw std::runtime_error("H2C PCIS descriptors are unavailable on this platform");
  }
};

namespace FPGAManagedStreams {

/** Mirrors midas.core.FPGAManagedStreamTarget; see there for rationale. */
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

/** One 512-bit H2C bridge stream, with its descriptor status on the OCL bus. */
struct H2CStreamParameters {
  std::string stream_name;
  uint32_t stream_id;
  uint32_t fpga_buffer_depth;
  uint64_t fifo_count_addr;
  uint64_t descriptor_done_addr;
  uint64_t descriptor_error_addr;

  H2CStreamParameters(const std::string &stream_name,
                      uint32_t stream_id,
                      uint32_t fpga_buffer_depth,
                      uint64_t fifo_count_addr,
                      uint64_t descriptor_done_addr,
                      uint64_t descriptor_error_addr)
      : stream_name(stream_name), stream_id(stream_id),
        fpga_buffer_depth(fpga_buffer_depth),
        fifo_count_addr(fifo_count_addr),
        descriptor_done_addr(descriptor_done_addr),
        descriptor_error_addr(descriptor_error_addr) {}
};

class CPUToFPGADriver : public CPUToFPGAStreamDriver {
public:
  CPUToFPGADriver(H2CStreamParameters &&params,
                  HostBuffer buffer,
                  FPGAManagedStreamIO &io)
      : params(std::move(params)), buffer(buffer), io(io) {}

  size_t push(void *src, size_t num_bytes, size_t required_bytes) override;
  void flush() override;
  void init() override;

private:
  H2CStreamParameters params;
  HostBuffer buffer;
  FPGAManagedStreamIO &io;
  uint32_t last_completed = 0;
  bool completion_initialized = false;
  size_t submitted = 0;
  size_t outstanding_bytes = 0;
  std::deque<size_t> in_flight;
  std::mutex state_mutex;
  void reclaim();
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
class FPGAManagedStreamWidget final : public HostStreamEngine {
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
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu,
      std::vector<FPGAManagedStreams::H2CStreamParameters> &&from_cpu);

private:
  /** Streams land in host DRAM; the driver polls and drains them. */
  void init_host_memory_streams(
      FPGAManagedStreamIO &io,
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu,
      std::vector<FPGAManagedStreams::H2CStreamParameters> &&from_cpu);

  /** Streams land in a peer FPGA's BAR4 (addresses: sysfs + plusargs). */
  void init_peer_fpga_streams(
      FPGAManagedStreamIO &io,
      const std::vector<std::string> &args,
      std::vector<FPGAManagedStreams::StreamParameters> &&to_cpu);

  uint64_t get_p2p_bar_address(const char *dir_name);
};

class BiDirectionalManagedStreamIO : public FPGAManagedStreamIO,
                                     public CPUManagedStreamIO {};

#endif // __BRIDGES_FPGA_MANAGED_STREAM_H
