// See LICENSE for license details.

#ifndef __ENDPOINT_H
#define __ENDPOINT_H

#include "simif.h"

// Endpoints are widgets that are directly exposed to simulation timing (they
// produce and/or consume simulation tokens). They must be tick()-ed for the
// simulation to make forward progress.

class endpoint_t
{
public:
  endpoint_t(simif_t* s): sim(s) { }
  virtual ~endpoint_t() {};
  // Initialize FPGA-hosted endpoint state -- this can't be done in the constructor
  virtual void init() = 0;
  // Does work that allows the endpoint to advance in simulation time (one or more cycles)
  virtual void tick() = 0;
  // Indicates the simulation should terminate.
  // Tie off to false if the endpoint will never call for the simulation to teriminate.
  virtual bool terminate() = 0;
  // If the endpoint calls for termination, encode a cause here. 0 = PASS All other
  // codes are endpoint-implementation defined
  virtual int exit_code() = 0;

protected:
  void write(size_t addr, data_t data) {
    sim->write(addr, data);
  }

  data_t read(size_t addr) {
    return sim->read(addr);
  }

  ssize_t pull(size_t addr, char *data, size_t size) {
    return sim->pull(addr, data, size);
  }

  ssize_t push(size_t addr, char *data, size_t size) {
    if (size == 0)
      return 0;
    return sim->push(addr, data, size);
  }

private:
  simif_t *sim;
};

#endif // __ENDPOINT_H
