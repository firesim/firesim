#ifndef __SIMIF_VERILATOR_H
#define __SIMIF_VERILATOR_H

#include "simif.h"
#include "mm.h"
#include "mmio.h"

class simif_emul_t : public virtual simif_t
{
  public:
    simif_emul_t() { }
    virtual ~simif_emul_t();
    virtual void init(int argc, char** argv, bool log = false, bool fast_loadmem = true);
    virtual int finish();

  protected:
    virtual void write(size_t addr, uint32_t data);
    virtual uint32_t read(size_t addr);
};

#endif // __SIMIF_VERILATOR_H
