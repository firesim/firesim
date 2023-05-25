// See LICENSE for license details.

#ifndef __MASTER_H
#define __MASTER_H

#include "core/widget.h"

#include <cstdint>
#include <string>
#include <vector>

class simif_t;

struct SIMULATIONMASTER_struct {
  uint64_t INIT_DONE;
  uint64_t PRESENCE_READ;
  uint64_t PRESENCE_WRITE;
};

class master_t final : public widget_t {
public:
  /// The identifier for the bridge type.
  static char KIND;

  master_t(simif_t &simif,
           const SIMULATIONMASTER_struct &mmio_addrs,
           unsigned index,
           const std::vector<std::string> &args);

  /**
   * Check whether the device has FireSim fingerprint string.
   */
  bool check_fingerprint();

  /**
   * Write new value to FireSim fingerprint string.
   */
  void write_fingerprint(uint32_t data);

  /**
   * Check whether the device is initialised.
   */
  bool is_init_done();

private:
  const SIMULATIONMASTER_struct mmio_addrs;
};

#endif // __MASTER_H
