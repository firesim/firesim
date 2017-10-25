// See LICENSE for license details.

#ifndef __SIMIF_VERILATOR_H
#define __SIMIF_VERILATOR_H

#include "simif.h"
#include "mm.h"
#include "mm_dramsim2.h"
#include "emul/mmio.h"

class simif_emul_t : public virtual simif_t
{
  public:
    simif_emul_t() { }
    virtual ~simif_emul_t();
    virtual void init(int argc, char** argv, bool log = false);
    virtual int finish();

  protected:
    virtual void write(size_t addr, data_t data);
    virtual data_t read(size_t addr);
    virtual ssize_t pread(size_t addr, char* data, size_t size);
};

#endif // __SIMIF_VERILATOR_H
