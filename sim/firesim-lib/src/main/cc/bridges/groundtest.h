//See LICENSE for license details
#ifndef __GROUNDTEST_H
#define __GROUNDTEST_H

#include "bridges/bridge_driver.h"

#ifdef GROUNDTESTBRIDGEMODULE_struct_guard
class groundtest_t: public bridge_driver_t
{
  public:
    groundtest_t(
        simif_t *sim, const std::vector<std::string> &args,
        GROUNDTESTBRIDGEMODULE_struct *mmio_addrs);
    ~groundtest_t();

    virtual void init();
    virtual void tick();
    virtual bool terminate() { return _success; }
    virtual int  exit_code() { return 0; }
    virtual void finish() {};

  private:
    bool _success = false;
    simif_t* sim;
    GROUNDTESTBRIDGEMODULE_struct *mmio_addrs;
};
#endif

#endif
