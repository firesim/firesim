// See LICENSE for license details.

#include <vector>

#include "TestHarness.h"

class TestResetPulseBridge final : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::unique_ptr<reset_pulse_t> rb;
  void add_bridge_driver(reset_pulse_t *bridge) override {
    assert(!rb && "multiple bridges registered");
    rb.reset(bridge);
  }

  // Since we rely on an assertion firing to catch a failure, just run a
  // similation that is at least the length of the expected pulse.
  void run_test() override {
    rb->init();
    step(2 * rb->get_max_pulse_length());
  }
};

TEST_MAIN(TestResetPulseBridge)
