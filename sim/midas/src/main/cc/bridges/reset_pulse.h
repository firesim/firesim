#ifndef __RESET_PULSE_H
#define __RESET_PULSE_H

struct RESETPULSEBRIDGEMODULE_struct {
  unsigned long pulseLength;
  unsigned long doneInit;
};

#include <vector>

#include "core/bridge_driver.h"

class reset_pulse_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  reset_pulse_t(simif_t &sim,
                const std::vector<std::string> &args,
                const RESETPULSEBRIDGEMODULE_struct &mmio_addrs,
                unsigned int max_pulse_length,
                unsigned int default_pulse_length,
                int reset_index);
  // Bridge interface
  void init() override;
  void tick() override {}
  bool terminate() override { return false; };
  int exit_code() override { return 0; };
  void finish() override {}

  unsigned get_max_pulse_length() const { return max_pulse_length; }

private:
  const RESETPULSEBRIDGEMODULE_struct mmio_addrs;
  const unsigned int max_pulse_length;
  const unsigned int default_pulse_length;

  unsigned int pulse_length = default_pulse_length;
};

#endif //__RESET_PULSE_H
