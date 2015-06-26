#ifndef __SIMIF_ZYNQ_H
#define __SIMIF_ZYNQ_H

#include "simif.h"

class simif_zynq_t : public simif_t
{
  public:
    simif_zynq_t(
      std::vector<std::string> args, 
      std::string prefix, 
      bool log = false);
    ~simif_zynq_t() { }

  private:
    virtual void load_mem(std::string filename);
    volatile uintptr_t* dev_vaddr;
    const static uintptr_t dev_paddr = 0x43C00000; 
  
  protected:
    virtual void poke_channel(size_t addr, biguint_t data);
    virtual biguint_t peek_channel(size_t addr);
    virtual void write_mem(size_t addr, biguint_t data);
    virtual biguint_t read_mem(size_t addr);
};

#endif // __SIMIF_ZYNQ_H
