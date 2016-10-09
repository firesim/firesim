#ifndef __SIMIF_VERILATOR_H
#define __SIMIF_VERILATOR_H

#include "simif.h"
#include "mm.h"
#include "mmio.h"

class simif_verilator_t : public virtual simif_t
{
  public:
    simif_verilator_t() { }
    virtual ~simif_verilator_t();
    virtual void init(int argc, char** argv, bool log = false);

  private:
    mmio_t* master;
    mm_t* slave;
  
  protected:
    virtual void write(size_t addr, uint32_t data);
    virtual uint32_t read(size_t addr);

  private:
    void tick();
};

#endif // __SIMIF_VERILATOR_H
