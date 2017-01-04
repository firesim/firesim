#ifndef __ENDPOINT_H
#define __ENDPOINT_H

#include "simif.h"

template < class T >
class endpoint_t
{
public:
  endpoint_t(simif_t* s): sim(s) { }
  virtual void send(T& data) = 0;
  virtual void recv(T& data) = 0;
  virtual void delta(size_t t) = 0;
  virtual bool stall() = 0;
  virtual bool done() = 0;

protected:
  inline void write(size_t addr, data_t data) {
    sim->write(addr, data);
  }
  
  inline data_t read(size_t addr) {
    return sim->read(addr);
  }

private:
  simif_t *sim;
};

#endif // __ENDPOINT_H
