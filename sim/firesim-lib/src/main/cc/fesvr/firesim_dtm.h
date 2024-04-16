// See LICENSE for license details
#ifndef __FIRESIM_DTM_H
#define __FIRESIM_DTM_H

#include "testchip_dtm.h"

// shcho: added firesim_loadmem_t struct
struct firesim_loadmem_t {
  firesim_loadmem_t() : addr(0), size(0) {}
  firesim_loadmem_t(size_t addr, size_t size) : addr(addr), size(size) {}
  size_t addr;
  size_t size;
};

class firesim_dtm_t final : public testchip_dtm_t {
public:
  firesim_dtm_t(int argc, char **argv, bool has_loadmem);
  ~firesim_dtm_t() {}

  bool busy() { return is_busy; };
  
  // shcho: copied from tsi (need to check which function is requried)
  // void tick();
  // void tick(bool out_valid, uint32_t out_bits, bool in_ready) { tick(); };

  bool recv_loadmem_write_req(firesim_loadmem_t &loadmem);
  bool recv_loadmem_read_req(firesim_loadmem_t &loadmem);
  void recv_loadmem_data(void *buf, size_t len);
  bool has_loadmem_reqs();
protected:
  void idle() override;

  void load_mem_write(addr_t addr, size_t nbytes, const void *src) override;
  void load_mem_read(addr_t addr, size_t nbytes, void *dst) override;
  
  // TODO: Check if chunk_aligh() should be added
  size_t chunk_align() override { return 8; }

  std::deque<firesim_loadmem_t> loadmem_write_reqs;
  std::deque<firesim_loadmem_t> loadmem_read_reqs;
  std::deque<char> loadmem_write_data;

  std::deque<uint32_t> loadmem_out_data;
private:
  size_t idle_counts;
  bool is_busy;
};
#endif // __FIRESIM_DTM_H
