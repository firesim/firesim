// See LICENSE for license details.

#ifndef __MASTER_H
#define __MASTER_H

#include <cstdint>

class simif_t;

typedef struct SIMULATIONMASTER_struct {
  uint64_t STEP;
  uint64_t DONE;
  uint64_t INIT_DONE;
} SIMULATIONMASTER_struct;

SIMULATIONMASTER_checks;

class master_t final {
public:
  master_t(simif_t *sim, const SIMULATIONMASTER_struct &mmio_addrs);

  /**
   * Check whether the device is initialised.
   */
  bool is_init_done();

  /**
   * Check whether the simulation is complete.
   */
  bool is_done();

  /**
   * Request the simulation to advance a given number of steps.
   */
  void step(size_t n, bool blocking);

private:
  simif_t *sim;
  const SIMULATIONMASTER_struct mmio_addrs;
};

#endif // __MASTER_H
