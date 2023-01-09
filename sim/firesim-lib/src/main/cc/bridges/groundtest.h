// See LICENSE for license details
#ifndef __GROUNDTEST_H
#define __GROUNDTEST_H

#include "core/bridge_driver.h"

typedef struct GROUNDTESTBRIDGEMODULE_struct {
  uint64_t success;
} GROUNDTESTBRIDGEMODULE_struct;

class groundtest_t : public bridge_driver_t {
public:
  groundtest_t(simif_t *sim,
               const std::vector<std::string> &args,
               const GROUNDTESTBRIDGEMODULE_struct &mmio_addrs);
  ~groundtest_t();

  virtual void init();
  virtual void tick();
  virtual bool terminate() { return _success; }
  virtual int exit_code() { return 0; }
  virtual void finish(){};

private:
  bool _success = false;
  simif_t *sim;
  const GROUNDTESTBRIDGEMODULE_struct mmio_addrs;
};

#endif
