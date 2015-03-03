#ifndef __SIMIF_ZEDBORAD_H
#define __SIMIF_ZEDBORAD_H

#include "simif.h"

class simif_zedboard_t : public simif_t
{
  public:
    simif_zedboard_t(
      std::vector<std::string> args, 
      std::string prefix, 
      bool log = false,
      bool check_sample = false);
    ~simif_zedboard_t() { }

  private:
    virtual void poke_host(uint32_t value);
    virtual bool peek_host_ready();
    virtual uint32_t peek_host();

    volatile uintptr_t* dev_vaddr;
    const static uintptr_t dev_paddr = 0x43C00000;
};

#endif // __SIMIF_ZEDBORAD_H
