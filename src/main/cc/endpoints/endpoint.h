// See LICENSE for license details.

#ifndef __ENDPOINT_H
#define __ENDPOINT_H

#include "simif.h"

class endpoint_t
{
public:
  endpoint_t(simif_t* s): sim(s) { }
  virtual void init() {}; // FIXME; should be pure;
  virtual void tick() = 0;
  virtual bool done() = 0;

protected:
  inline void write(size_t addr, data_t data) {
    sim->write(addr, data);
  }
  
  inline data_t read(size_t addr) {
    return sim->read(addr);
  }

  inline ssize_t pull(size_t addr, char *data, size_t size) {
    return sim->pull(addr, data, size);
  }

  inline ssize_t push(size_t addr, char *data, size_t size) {
    if (size == 0)
      return 0;
    return sim->push(addr, data, size);
  }

private:
  simif_t *sim;
};

#endif // __ENDPOINT_H
