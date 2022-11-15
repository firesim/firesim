// See LICENSE for license details

#ifndef __SIMPLENIC_H
#define __SIMPLENIC_H

#include "bridges/bridge_driver.h"
#include <vector>

// TODO this should not be hardcoded here.
#define MAX_BANDWIDTH 200

#ifdef SIMPLENICBRIDGEMODULE_struct_guard

#define INSTANTIATE_SIMPLENIC(FUNC, IDX)                                       \
  SIMPLENICBRIDGEMODULE_##IDX##_substruct_create;                              \
  FUNC(new simplenic_t(this,                                                   \
                       args,                                                   \
                       SIMPLENICBRIDGEMODULE_##IDX##_substruct,                \
                       IDX,                                                    \
                       SIMPLENICBRIDGEMODULE_##IDX##_to_cpu_stream_idx,        \
                       SIMPLENICBRIDGEMODULE_##IDX##_to_cpu_stream_depth,      \
                       SIMPLENICBRIDGEMODULE_##IDX##_from_cpu_stream_idx,      \
                       SIMPLENICBRIDGEMODULE_##IDX##_from_cpu_stream_depth));

class simplenic_t : public bridge_driver_t {
public:
  simplenic_t(simif_t *sim,
              std::vector<std::string> &args,
              SIMPLENICBRIDGEMODULE_struct *addrs,
              int simplenicno,
              const int stream_to_cpu_idx,
              const int stream_to_cpu_depth,
              const int stream_from_cpu_idx,
              const int stream_from_cpu_depth);
  ~simplenic_t();

  virtual void init();
  virtual void tick();
  virtual bool terminate() { return false; };
  virtual int exit_code() { return 0; }
  virtual void finish(){};

private:
  uint64_t mac_lendian;
  char *pcis_read_bufs[2];
  char *pcis_write_bufs[2];
  int rlimit_inc, rlimit_period, rlimit_size;
  int pause_threshold, pause_quanta, pause_refresh;

  // link latency in cycles
  // assuming 3.2 GHz, this number / 3.2 = link latency in ns
  // e.g. setting this to 6405 gives you 6405/3.2 = 2001.5625 ns latency
  // IMPORTANT: this must be a multiple of 7
  int LINKLATENCY;
  FILE *niclog;
  SIMPLENICBRIDGEMODULE_struct *mmio_addrs;
  bool loopback;

  // checking for token loss
  int currentround = 0;

  // only for TOKENVERIFY
  const int stream_to_cpu_idx;
  const int stream_from_cpu_idx;
};
#endif // SIMPLENICBRIDGEMODULE_struct_guard

#endif // __SIMPLENIC_H
