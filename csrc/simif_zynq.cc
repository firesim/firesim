#include "simif_zynq.h"
#include <cassert>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#define read_reg(r) (dev_vaddr[r])
#define write_reg(r, v) (dev_vaddr[r] = v)

simif_zynq_t::simif_zynq_t(std::vector<std::string> args, std::string prefix, bool log)
  : simif_t(args, prefix, log)
{
  int fd = open("/dev/mem", O_RDWR|O_SYNC);
  assert(fd != -1);

  int host_prot = PROT_READ | PROT_WRITE;
  int flags = MAP_SHARED;
  uintptr_t pgsize = sysconf(_SC_PAGESIZE);
  assert(dev_paddr % pgsize == 0);

  dev_vaddr = (uintptr_t*)mmap(0, pgsize, host_prot, flags, fd, dev_paddr);
  assert(dev_vaddr != MAP_FAILED);

  init();
}

void simif_zynq_t::poke_channel(size_t addr, uint64_t data) {
  write_reg(addr, data);
  __sync_synchronize();
}

uint64_t simif_zynq_t::peek_channel(size_t addr) {
  __sync_synchronize();
  return read_reg(addr);
}

void simif_zynq_t::send_tokens(uint32_t* const map, size_t size, size_t off) {
  for (size_t i = 0 ; i < size ; i++) {
    write_reg(off+i, map[i]);
  }
}

void simif_zynq_t::recv_tokens(uint32_t* const map, size_t size, size_t off) {
  __sync_synchronize();
  for (size_t i = 0 ; i < size ; i++) {
    map[i] = read_reg(off+i);
  }
}
