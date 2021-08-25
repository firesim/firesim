#ifndef __SIMIF_XDMA_H
#define __SIMIF_XDMA_H

#include "simif.h"    // from midas

constexpr size_t ctrl_as_size = 1024 * 1024;

class simif_xdma_t: public virtual simif_t
{
  public:
    simif_xdma_t(int argc, char** argv);
    virtual ~simif_xdma_t();
    virtual void write(size_t addr, uint32_t data);
    virtual uint32_t read(size_t addr);
    virtual ssize_t pull(size_t addr, char* data, size_t size);
    virtual ssize_t push(size_t addr, char* data, size_t size);
  private:
    void fpga_shutdown();
    void fpga_setup(int slot_id);
    int slot_id;
    int ctrl_fd;
    int xdma_write_fd;
    int xdma_read_fd;
    void* ctrl_base_vaddr;
};

#endif // __SIMIF_XDMA_H
