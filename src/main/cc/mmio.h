#ifndef __MMIO_H
#define __MMIO_H

#include <stdint.h>

class mmio_t
{
public:
  virtual void read_req(uint64_t addr) = 0;
  virtual void write_req(uint64_t addr, void* data) = 0;
  virtual bool read_resp(void *data) = 0;
  virtual bool write_resp() = 0;
};

void init(uint64_t memsize, bool dram);

#endif // __MMIO_H
