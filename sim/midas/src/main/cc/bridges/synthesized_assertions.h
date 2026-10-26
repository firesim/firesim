#ifndef __SYNTHESIZED_ASSERTIONS_H
#define __SYNTHESIZED_ASSERTIONS_H

#include "core/bridge_driver.h"

#include <string>
#include <vector>

struct ASSERTBRIDGEMODULE_struct {
  uint64_t id;
  uint64_t fire;
  uint64_t cycle_low;
  uint64_t cycle_high;
  uint64_t resume;
  uint64_t enable;
};

class synthesized_assertions_t final : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  /// A target value labeled with AssertContext: reported when an assertion
  /// fires and at the end of the simulation.
  struct context_t {
    const char *label;
    uint32_t addr_low;
    uint32_t addr_high;
  };

  synthesized_assertions_t(simif_t &sim,
                           const ASSERTBRIDGEMODULE_struct &mmio_addrs,
                           unsigned index,
                           const std::vector<std::string> &args,
                           std::vector<const char *> &&msgs,
                           std::vector<context_t> &&contexts);
  ~synthesized_assertions_t() override;

  void init() override;
  void tick() override;
  std::string exit_summary() override;

  bool terminate() override { return assert_fired; };
  int exit_code() override { return (assert_fired) ? assert_id + 1 : 0; };

  // Clears any set assertions, and allows the simulation to advance
  void resume();

private:
  bool assert_fired = false;
  bool enable = true;
  int assert_id;
  uint64_t assert_cycle;
  const ASSERTBRIDGEMODULE_struct mmio_addrs;
  std::vector<const char *> msgs;
  std::vector<context_t> contexts;

  // The context values as "label=value, ...", read from the bridge
  std::string format_contexts();
};

#endif //__SYNTHESIZED_ASSERTIONS_H
