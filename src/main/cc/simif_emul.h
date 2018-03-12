// See LICENSE for license details.

#ifndef __SIMIF_VERILATOR_H
#define __SIMIF_VERILATOR_H

#include <memory>

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

    virtual void write(size_t addr, data_t data);
    virtual data_t read(size_t addr);
    virtual ssize_t pull(size_t addr, char* data, size_t size);
    virtual ssize_t push(size_t addr, char* data, size_t size);

  private:
    void wait_read(std::unique_ptr<mmio_t>& mmio, void *data);
    void wait_write(std::unique_ptr<mmio_t>& mmio);
};

#endif // __SIMIF_VERILATOR_H
