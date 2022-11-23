// See LICENSE for license details.

#include "bridges/reset_pulse.h"
#include "simif_peek_poke.h"

#include <vector>

class ResetPulseBridgeTest_t : public simif_peek_poke_t {
public:
  reset_pulse_t *rb;
  // Define a dummy function so we can use the instantiation macro
  void register_rb(reset_pulse_t *new_rb) { rb = new_rb; }

  ResetPulseBridgeTest_t(const std::vector<std::string> &args, simif_t *simif)
      : simif_peek_poke_t(simif, PEEKPOKEBRIDGEMODULE_0_substruct_create) {
    INSTANTIATE_RESET_PULSE(register_rb, 0)
  }

  // Since we rely on an assertion firing to catch a failure, just run a
  // similation that is at least the length of the expected pulse.
  void run() {
    rb->init();
    step(2 * RESETPULSEBRIDGEMODULE_0_max_pulse_length);
  }
};
