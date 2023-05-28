// See LICENSE for license details.

#ifndef __BRIDGE_DRIVER_H
#define __BRIDGE_DRIVER_H

#include "core/simif.h"
#include "core/stream_engine.h"

/**
 * Utility to create static buffers aligned to and a multiple of the page-size
 */
#define page_size 4096
#define page_aligned_sized_array(__NAME__, __SIZE_IN_BYTES__)                  \
  alignas(page_size) char(                                                     \
      __NAME__)[(((__SIZE_IN_BYTES__) + page_size - 1) / page_size) *          \
                page_size];

// DOC include start: Bridge Driver Interface
/**
 * @brief Base class for Bridge Drivers
 *
 * Bridge Drivers are the CPU-hosted component of a Target-to-Host Bridge. A
 * Bridge Driver interacts with their accompanying FPGA-hosted BridgeModule
 * using MMIO (via read() and write() methods).
 */
class bridge_driver_t : public widget_t {
public:
  using widget_t::widget_t;

  ~bridge_driver_t() override = default;

  /**
   * Initialize the bridge state.
   *
   * This method can be overriden to initialise the hardware through MMIO.
   */
  virtual void init() {}

  /**
   * Final post-run cleanup.
   *
   * The analog of init(), this provides a final opportunity to interact with
   * the FPGA before destructors are called at the end of simulation. Useful
   * for doing end-of-simulation clean up that requires communication with
   * the hardware through MMIO or streams.
   */
  virtual void finish() {}

  /**
   * Does work that allows the bridge to advance in simulation time.
   *
   * Progresses for one or more cycles. The standard FireSim driver calls the
   * tick methods of all registered bridge drivers. Bridges whose BridgeModule
   * is free-running need not implement this method
   */
  virtual void tick() {}

  /**
   * Returns true if the bridge requests explicit termination.
   */
  virtual bool terminate() { return false; }

  /**
   * If the bridge driver calls for termination, encode a cause here.
   *
   * 0 = PASS. All other codes are bridge-implementation defined
   */
  virtual int exit_code() { return 0; }

protected:
  void write(size_t addr, uint32_t data);

  uint32_t read(size_t addr);
};
// DOC include end: Bridge Driver Interface

/**
 * Bridge driver which interacts with the BridgeModule through streams.
 *
 * Information from streams is acquired using the `pull` and `push` methods.
 */
class streaming_bridge_driver_t : public bridge_driver_t {
public:
  streaming_bridge_driver_t(simif_t &s, StreamEngine &stream, const void *kind)
      : bridge_driver_t(s, kind), stream(stream) {}

  /**
   *  @brief Logical byte width of Bridge streams
   *
   * Bridge streams are logically latency-insensitive FIFOs with a width of
   * \c STREAM_WIDTH_BYTES.
   * \note { The host-implementation may use a different width under-the-hood
   * but this should not be exposed to bridge developers. }
   */
  static constexpr unsigned STREAM_WIDTH_BYTES = 64;

protected:
  size_t
  pull(unsigned stream_idx, void *data, size_t size, size_t minimum_batch_size);

  size_t
  push(unsigned stream_idx, void *data, size_t size, size_t minimum_batch_size);

  void pull_flush(unsigned stream_idx);

private:
  StreamEngine &stream;
};

#endif // __BRIDGE_DRIVER_H
