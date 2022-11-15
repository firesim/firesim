#ifndef __SYNTHESIZED_ASSERTIONS_H
#define __SYNTHESIZED_ASSERTIONS_H

#ifdef ASSERTBRIDGEMODULE_struct_guard

#include "bridge_driver.h"

class synthesized_assertions_t : public bridge_driver_t {
public:
  synthesized_assertions_t(simif_t *sim,
                           std::vector<std::string> &args,
                           ASSERTBRIDGEMODULE_struct *mmio_addrs,
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
  ASSERTBRIDGEMODULE_struct *mmio_addrs;
  const char *const *msgs;
};

#endif // ASSERTBRIDGEMODULE_struct_guard

#endif //__SYNTHESIZED_ASSERTIONS_H
