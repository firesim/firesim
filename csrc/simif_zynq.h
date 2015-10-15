#ifndef __SIMIF_ZYNQ_H
#define __SIMIF_ZYNQ_H

#include "simif.h"

class simif_zynq_t : public simif_t
{
  public:
    simif_zynq_t(std::vector<std::string> args, std::string prefix, bool log = false);
    ~simif_zynq_t() { finish(); }

  private:
    volatile uintptr_t* dev_vaddr;
    const static uintptr_t dev_paddr = 0x43C00000; 
  
  protected:
    virtual void poke_channel(size_t addr, uint64_t data);
    virtual uint64_t peek_channel(size_t addr);
    virtual void send_tokens(uint32_t* const map, size_t size, size_t off);
    virtual void recv_tokens(uint32_t* const map, size_t size, size_t off);
};

#endif // __SIMIF_ZYNQ_H
