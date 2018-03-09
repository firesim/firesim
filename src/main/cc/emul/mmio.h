// See LICENSE for license details.

#ifndef __MMIO_H
#define __MMIO_H

#include <stdint.h>
#include <stddef.h>

class mmio_t
{
public:
  virtual void read_req(uint64_t addr, size_t size, size_t len) = 0;
  virtual void write_req(uint64_t addr, size_t size, size_t len, void* data, size_t *strb) = 0;
  virtual bool read_resp(void *data) = 0;
  virtual bool write_resp() = 0;
};

void* init(uint64_t memsize, bool dram);

#endif // __MMIO_H
