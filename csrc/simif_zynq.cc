#include "simif_zynq.h"
#include <assert.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <fstream>

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

  // Reset
  poke_channel(RESET_ADDR, 0);
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

