#ifndef __SIMIF_CATAPULT_H
#define __SIMIF_CATAPULT_H

#include "simif.h"    // from midas
#include "catapult.h" // from catapult-protect

class simif_catapult_t: public virtual simif_t
{
  public:
    simif_catapult_t();
    virtual ~simif_catapult_t();
    virtual void write(size_t addr, uint32_t data);
    virtual uint32_t read(size_t addr);
  private:
    char in_buf[MMIO_WIDTH];
    char out_buf[MMIO_WIDTH];
};

#endif // __SIMIF_CATAPULT_H
