#ifndef __RESET_PULSE_H
#define __RESET_PULSE_H

struct RESETPULSEBRIDGEMODULE_struct {
  unsigned long pulseLength;
  unsigned long doneInit;
};

#include <vector>

#include "core/bridge_driver.h"

class reset_pulse_t final : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  reset_pulse_t(simif_t &sim,
                const RESETPULSEBRIDGEMODULE_struct &mmio_addrs,
                unsigned index,
                const std::vector<std::string> &args,
                unsigned int max_pulse_length,
                unsigned int default_pulse_length);

  // Bridge interface
  void init() override;

  unsigned get_max_pulse_length() const { return max_pulse_length; }

private:
  const RESETPULSEBRIDGEMODULE_struct mmio_addrs;
  const unsigned int max_pulse_length;
  const unsigned int default_pulse_length;

  unsigned int pulse_length = default_pulse_length;
};

#endif //__RESET_PULSE_H
