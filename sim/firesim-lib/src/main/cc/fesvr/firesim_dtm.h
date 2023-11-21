// See LICENSE for license details
#ifndef __FIRESIM_DTM_H
#define __FIRESIM_DTM_H

#include "testchip_dtm.h"

class firesim_dtm_t final : public testchip_dtm_t {
public:
  firesim_dtm_t(int argc, char **argv, bool has_loadmem);
  ~firesim_dtm_t() {}

  bool busy() { return is_busy; };

protected:
  void idle() override;

  void load_mem_write(addr_t addr, size_t nbytes, const void *src) override;
  void load_mem_read(addr_t addr, size_t nbytes, void *dst) override;

private:
  size_t idle_counts;
  bool is_busy;
};
#endif // __FIRESIM_DTM_H
