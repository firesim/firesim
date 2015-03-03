#include <assert.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include "simif_zedboard.h"

#define read_reg(r) (dev_vaddr[r])
#define write_reg(r, v) (dev_vaddr[r] = v)

simif_zedboard_t::simif_zedboard_t(
  std::vector<std::string> args, 
  std::string prefix, 
  bool log, 
  bool check_sample)
  : simif_t(args, prefix, log, check_sample)
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
  write_reg(31, 0);
  __sync_synchronize();
  // Empty output queues before starting!
  while ((uint32_t) read_reg(0) > 0) {
    read_reg(1);
  }
}

void simif_zedboard_t::poke_host(uint32_t value) {
  write_reg(0, value);
  __sync_synchronize();
}

bool simif_zedboard_t::peek_host_ready() {
  return (uint32_t) read_reg(0) != 0;
}

uint32_t simif_zedboard_t::peek_host() {
  __sync_synchronize();
  while (!peek_host_ready()) ;
  return (uint32_t) read_reg(1);
}
