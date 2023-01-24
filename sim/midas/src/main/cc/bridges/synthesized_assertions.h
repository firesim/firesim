#ifndef __SYNTHESIZED_ASSERTIONS_H
#define __SYNTHESIZED_ASSERTIONS_H

#include "core/bridge_driver.h"

struct ASSERTBRIDGEMODULE_struct {
  uint64_t id;
  uint64_t fire;
  uint64_t cycle_low;
  uint64_t cycle_high;
  uint64_t resume;
  uint64_t enable;
};

class synthesized_assertions_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  synthesized_assertions_t(simif_t &sim,
                           const std::vector<std::string> &args,
                           const ASSERTBRIDGEMODULE_struct &mmio_addrs,
                           const char *const *msgs);
  ~synthesized_assertions_t() override;
  void init() override;
  void tick() override;
  void finish() override {}
  void
  resume(); // Clears any set assertions, and allows the simulation to advance
  bool terminate() override { return assert_fired; };
  int exit_code() override { return (assert_fired) ? assert_id + 1 : 0; };

private:
  bool assert_fired = false;
  bool enable = true;
  int assert_id;
  uint64_t assert_cycle;
  const ASSERTBRIDGEMODULE_struct mmio_addrs;
  const char *const *msgs;
};

#endif //__SYNTHESIZED_ASSERTIONS_H
