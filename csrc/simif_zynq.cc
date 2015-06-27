#include <assert.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <fstream>
#include "simif_zynq.h"

#define read_reg(r) (dev_vaddr[r])
#define write_reg(r, v) (dev_vaddr[r] = v)

simif_zynq_t::simif_zynq_t(
  std::vector<std::string> args, 
  std::string prefix, 
  bool log)
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

void simif_zynq_t::poke_channel(size_t addr, biguint_t data) {
  uint64_t mask = (uint64_t(1) << AXI_DATA_WIDTH) - 1;
  size_t limit = (addr == RESET_ADDR) ? 1 : (in_widths[addr] - 1) / AXI_DATA_WIDTH + 1;
  for (ssize_t i = limit - 1 ; i >= 0 ; i--) {
    uint64_t masked_data = ((data >> (i * AXI_DATA_WIDTH)) & mask).uint();
    write_reg(addr, masked_data);
    __sync_synchronize();
  }
}

biguint_t simif_zynq_t::peek_channel(size_t addr) {
  biguint_t data = 0;
  size_t limit = (out_widths[addr] - 1) / AXI_DATA_WIDTH + 1;
  for (size_t i = 0 ; i < limit ; i++) {
    __sync_synchronize();
    data |= biguint_t(read_reg(addr)) << (i * AXI_DATA_WIDTH);
  }
  return data;
}

void simif_zynq_t::write_mem(size_t addr, biguint_t data) {
  poke_channel(req_map["mem_req_cmd_addr"], addr >> MEM_BLOCK_OFFSET);
  poke_channel(req_map["mem_req_cmd_tag"], 1);
  poke_channel(req_map["mem_req_data"], data);
}

biguint_t simif_zynq_t::read_mem(size_t addr) {
  poke_channel(req_map["mem_req_cmd_addr"], addr >> MEM_BLOCK_OFFSET);
  poke_channel(req_map["mem_req_cmd_tag"], 0);
  assert(peek_channel(resp_map["mem_resp_tag"]) == 0);
  return peek_channel(resp_map["mem_resp_data"]);
}

void simif_zynq_t::load_mem(std::string filename) {
  const size_t step = AXI_DATA_WIDTH >> 2; // -> AXI_DATA_WIDTH / 4
  std::ifstream file(filename.c_str());
  if (file) {
    std::string line;
    int i = 0;
    while (std::getline(file, line)) {
      uint64_t base = (i * line.length()) / 2;
      size_t offset = 0;
      for (int j = line.length() - step ; j >= 0 ; j -= step) {
        biguint_t data = 0;
        for (int k = 0 ; k < step ; k++) {
          data |= parse_nibble(line[j+k]) << (4*(step-1-k));
        }
        write_mem(base+offset, data);
        offset += step >> 1; // -> step / 2
      }
      i = 1;
    }
  } else {
    fprintf(stderr, "Cannot open %s\n", filename.c_str());
    exit(1);
  }
  file.close();
}

