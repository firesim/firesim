// See LICENSE for license details.

#ifndef __CLOCK_H
#define __CLOCK_H

#include <cstdint>

class simif_t;

typedef struct CLOCKBRIDGEMODULE_struct {
  uint64_t hCycle_0;
  uint64_t hCycle_1;
  uint64_t hCycle_latch;
  uint64_t tCycle_0;
  uint64_t tCycle_1;
  uint64_t tCycle_latch;
} CLOCKBRIDGEMODULE_struct;

CLOCKBRIDGEMODULE_checks;

class clockmodule_t final {
public:
  clockmodule_t(simif_t *sim, const CLOCKBRIDGEMODULE_struct &mmio_addrs);

  /**
   * Provides the current target cycle of the fastest clock.
   *
   * The target cycle is based on the number of clock tokens enqueued
   * (will report a larger number).
   */
  uint64_t tcycle();

  /**
   * Returns the current host cycle as measured by a hardware counter
   */
  uint64_t hcycle();

private:
  simif_t *sim;
  const CLOCKBRIDGEMODULE_struct mmio_addrs;
};

#endif // __CLOCK_H
