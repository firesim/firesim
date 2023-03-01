
#ifndef __TERMINATION_H
#define __TERMINATION_H
// See LICENSE for license details.

#include "core/bridge_driver.h"

struct TERMINATIONBRIDGEMODULE_struct {
  uint64_t out_counter_0;
  uint64_t out_counter_1;
  uint64_t out_counter_latch;
  uint64_t out_status;
  uint64_t out_terminationCode;
};

struct termination_message_t {
  const bool is_err;
  const std::string msg;
};

class termination_t : public bridge_driver_t {
public:
  /// The identifier for the bridge type used for casts.
  static char KIND;

  termination_t(simif_t &sim,
                const TERMINATIONBRIDGEMODULE_struct &mmio_addrs,
                unsigned index,
                const std::vector<std::string> &args,
                const std::vector<termination_message_t> &messages);

  ~termination_t() override;

  void tick() override;
  bool terminate() override { return test_done; }
  int exit_code() override { return fail; }

  const char *exit_message();
  int cycle_count();

private:
  const TERMINATIONBRIDGEMODULE_struct mmio_addrs;
  bool test_done = false;
  int fail = 0;
  int tick_rate = 10;
  int tick_counter = 0;
  std::vector<termination_message_t> messages;
};

#endif //__TERMINATION_H
