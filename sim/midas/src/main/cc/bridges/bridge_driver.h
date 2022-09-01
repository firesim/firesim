// See LICENSE for license details.

#ifndef __BRIDGE_DRIVER_H
#define __BRIDGE_DRIVER_H

#include "simif.h"

namespace BridgeConstants {
/**
 *  @brief Logical byte width of Bridge streams
 *
 * Bridge streams are logically latency-insensitive FIFOs with a width of
 * \c STREAM_WIDTH_BYTES.
 * \note { The host-implementation may use a different width under-the-hood but
 * this should not be exposed to bridge developers. }
 */
constexpr int STREAM_WIDTH_BYTES = 64;
} // namespace BridgeConstants

// DOC include start: Bridge Driver Interface
/**
 * @brief Base class for Bridge Drivers
 *
 * Bridge Drivers are the CPU-hosted component of a Target-to-Host Bridge. A
 * Bridge Driver interacts with their accompanying FPGA-hosted BridgeModule
 * using MMIO (via read() and write() methods) or bridge streams (via pull()
 * and push()).
 */
class bridge_driver_t {
public:
  bridge_driver_t(simif_t *s) : sim(s) {}
  virtual ~bridge_driver_t(){};
  // Initialize BridgeModule state -- this can't be done in the constructor
  // currently
  virtual void init() = 0;
  // Does work that allows the Bridge to advance in simulation time (one or more
  // cycles) The standard FireSim driver calls the tick methods of all
  // registered bridge drivers. Bridges whose BridgeModule is free-running need
  // not implement this method
  virtual void tick() = 0;
  // Indicates the simulation should terminate.
  // Tie off to false if the brige will never call for the simulation to
  // teriminate.
  virtual bool terminate() = 0;
  // If the bridge driver calls for termination, encode a cause here. 0 = PASS
  // All other codes are bridge-implementation defined
  virtual int exit_code() = 0;
  // The analog of init(), this provides a final opportunity to interact with
  // the FPGA before destructors are called at the end of simulation. Useful
  // for doing end-of-simulation clean up that requires calling
  // {read,write,push,pull}.
  virtual void finish() = 0;
  // DOC include end: Bridge Driver Interface

protected:
  void write(size_t addr, uint32_t data) { sim->write(addr, data); }

  uint32_t read(size_t addr) { return sim->read(addr); }

  size_t pull(unsigned stream_idx,
              void *data,
              size_t size,
              size_t minimum_batch_size) {
    return sim->pull(stream_idx, data, size, minimum_batch_size);
  }

  size_t push(unsigned stream_idx,
              void *data,
              size_t size,
              size_t minimum_batch_size) {
    if (size == 0)
      return 0;
    return sim->push(stream_idx, data, size, minimum_batch_size);
  }

private:
  simif_t *sim;
};

#endif // __BRIDGE_DRIVER_H
