#ifndef __HEARTBEAT_H
#define __HEARTBEAT_H

#include <fstream>

#include "core/bridge_driver.h"

// Periodically checks that the target is advancing by polling an FPGA-hosted
// cycle count, which it writes out to a file.  The causes of an apparently
// hung simulator can be coarsely deduced from the behavior of this class and
// whether the simulation terminates.
//
// See
// docs/Advanced-Usage/Debugging-and-Profiling-on-FPGA/Debugging-Hanging-Simulators.rst
// for expanded discussion.

class heartbeat_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  heartbeat_t(simif_t &sim, const std::vector<std::string> &args);

  void init() override {}
  void tick() override;
  bool terminate() override { return has_timed_out; };
  int exit_code() override { return (has_timed_out) ? 1 : 0; };
  void finish() override {}

private:
  std::ofstream log;
  time_t start_time;

  bool has_timed_out = false;
  // Arbitrary selection; O(10) wallclock seconds for default targets during
  // linux boot
  uint64_t polling_interval = 10e5;
  uint64_t trip_count = 0;
  uint64_t last_cycle = 0;
};

#endif //__HEARTBEAT_H
