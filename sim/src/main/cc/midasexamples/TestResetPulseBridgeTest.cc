// See LICENSE for license details.

#include "TestHarness.h"
#include "bridges/reset_pulse.h"

#include <vector>

class TestResetPulseBridge final : public TestHarness {
public:
  using TestHarness::TestHarness;

  reset_pulse_t &rb = get_bridge<reset_pulse_t>();

  // Since we rely on an assertion firing to catch a failure, just run a
  // similation that is at least the length of the expected pulse.
  void run_test() override {
    rb.init();
    step(2 * rb.get_max_pulse_length());
  }
};

TEST_MAIN(TestResetPulseBridge)
