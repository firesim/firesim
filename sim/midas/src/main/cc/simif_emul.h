// See LICENSE for license details.

#ifndef __SIMIF_EMUL_H
#define __SIMIF_EMUL_H

#include <memory>

#include "simif.h"
#include "mm.h"
#include "mm_dramsim2.h"
#include "emul/mmio.h"

// simif_emul_t is a concrete simif_t implementation for Software RTL simulators
// The basis for MIDAS-level simulation
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
    // The maximum number of cycles the RTL simulator can advance before
    // switching back to the driver process. +fuzz-host-timings sets this to a value > 1, introducing random delays
    // in MMIO (read, write) and DMA (push, pull) requests
    int maximum_host_delay = 1;
    void advance_target();
    void wait_read(std::unique_ptr<mmio_t>& mmio, void *data);
    void wait_write(std::unique_ptr<mmio_t>& mmio);
};

#endif // __SIMIF_EMUL_H
