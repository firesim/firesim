// See LICENSE for license details
#ifndef __SERIAL_H
#define __SERIAL_H

#include "bridges/serial_data.h"
#include "core/bridge_driver.h"

class loadmem_t;
class firesim_tsi_t;
class firesim_loadmem_t;

struct SERIALBRIDGEMODULE_struct {
  uint64_t in_bits;
  uint64_t in_valid;
  uint64_t in_ready;
  uint64_t out_bits;
  uint64_t out_valid;
  uint64_t out_ready;
  uint64_t step_size;
  uint64_t done;
  uint64_t start;
};

class serial_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  serial_t(simif_t &simif,
           const std::vector<std::string> &args,
           const SERIALBRIDGEMODULE_struct &mmio_addrs,
           loadmem_t &loadmem_widget,
           bool has_mem,
           int64_t mem_host_offset,
           int serialno);
  ~serial_t();
  virtual void init();
  virtual void tick();
  virtual bool terminate();
  virtual int exit_code();
  virtual void finish() {}

private:
  const SERIALBRIDGEMODULE_struct mmio_addrs;
  loadmem_t &loadmem_widget;

  firesim_tsi_t *fesvr;
  bool has_mem;
  // host memory offset based on the number of memory models and their size
  int64_t mem_host_offset;
  // Number of target cycles between fesvr interactions
  uint32_t step_size;

  // Arguments passed to firesim_tsi.
  char **tsi_argv = nullptr;
  int tsi_argc;

  // Tell the widget to start enqueuing tokens
  void go();
  // Moves data to and from the widget and fesvr
  void send(); // FESVR -> Widget
  void recv(); // Widget -> FESVR

  // Helper functions to handoff fesvr requests to the loadmem unit
  void handle_loadmem_read(firesim_loadmem_t loadmem);
  void handle_loadmem_write(firesim_loadmem_t loadmem);
  void serial_bypass_via_loadmem();
};

#endif // __SERIAL_H
