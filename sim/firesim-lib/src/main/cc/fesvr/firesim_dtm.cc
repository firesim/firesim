// See LICENSE for license details
#include "firesim_dtm.h"

firesim_dtm_t::firesim_dtm_t(int argc, char **argv, bool has_loadmem)
    : testchip_dtm_t(argc, argv, has_loadmem), is_busy(false) {
  idle_counts = 10;
  std::vector<std::string> args(argv + 1, argv + argc);
  for (auto &arg : args) {
    if (arg.find("+idle-counts=") == 0)
      idle_counts = atoi(arg.c_str() + 13);
  }
}

void firesim_dtm_t::idle() {
  is_busy = false;
  for (size_t i = 0; i < idle_counts; i++)
    switch_to_target();
  is_busy = true;
}

void firesim_dtm_t::load_mem_write(addr_t addr,
                                   size_t nbytes,
                                   const void *src) {
  assert(false && "dtm_t doesn't support loadmem requests");
}

void firesim_dtm_t::load_mem_read(addr_t addr, size_t nbytes, void *dst) {
  assert(false && "dtm_t doesn't support loadmem requests");
}
