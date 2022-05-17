//See LICENSE for license details.

#include "simif_peek_poke.h"
#include "bridges/reset_pulse.h"

#include <vector>

class ResetPulseBridgeTest_t: public simif_peek_poke_t {
  public:
    reset_pulse_t * rb;
    // Define a dummy function so we can use the instantiation macro
    void register_rb(reset_pulse_t * new_rb) { rb = new_rb; }

    ResetPulseBridgeTest_t(int argc, char** argv) {
      std::vector<std::string> args(argv + 1, argv + argc);
      INSTANTIATE_RESET_PULSE(register_rb, 0)
    }
    // Since we rely on an assertion firing to catch a failure, just run a
    // similation that is at least the length of the expected pulse.
    void run() {
      rb->init();
      step(2 * RESETPULSEBRIDGEMODULE_0_max_pulse_length);
    }
};
