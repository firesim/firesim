#ifndef __SYNTHESIZED_ASSERTIONS_H
#define __SYNTHESIZED_ASSERTIONS_H

#include "bridge_driver.h"

typedef struct ASSERTBRIDGEMODULE_struct {
  uint64_t id;
  uint64_t fire;
  uint64_t cycle_low;
  uint64_t cycle_high;
  uint64_t resume;
  uint64_t enable;
} ASSERTBRIDGEMODULE_struct;

#ifdef ASSERTBRIDGEMODULE_checks
ASSERTBRIDGEMODULE_checks;
#endif // ASSERTBRIDGEMODULE_checks

class synthesized_assertions_t : public bridge_driver_t {
public:
  synthesized_assertions_t(simif_t *sim,
                           const std::vector<std::string> &args,
                           const ASSERTBRIDGEMODULE_struct &mmio_addrs,
                           const char *const *msgs);
  ~synthesized_assertions_t();
  virtual void init();
  virtual void tick();
  virtual void finish(){};
  void
  resume(); // Clears any set assertions, and allows the simulation to advance
  virtual bool terminate() { return assert_fired; };
  virtual int exit_code() { return (assert_fired) ? assert_id + 1 : 0; };

private:
  bool assert_fired = false;
  bool enable = true;
  int assert_id;
  uint64_t assert_cycle;
  const ASSERTBRIDGEMODULE_struct mmio_addrs;
  const char *const *msgs;
};

#endif //__SYNTHESIZED_ASSERTIONS_H
