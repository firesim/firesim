// See LICENSE for license details.

#include "TestHarness.h"

class TestTerminationModuleAssert final : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::unique_ptr<termination_t> terminator;
  void add_bridge_driver(termination_t *bridge) override {
    assert(!terminator && "multiple bridges registered");
    terminator.reset(bridge);
  }

  int expected_cycle_at_bridge = 0;

  // Steps the DUT assuming the termination bridge should not call for teardown
  void step_assume_continue(int count) {
    step(count, false);
    expected_cycle_at_bridge += count;
    while (!simif->done()) {
      terminator->tick();
      assert(!terminator->terminate() && "Unexpected termination signaled.");
    }
  }

  // Assumes termination should occur in the current cycle
  void step_once_and_wait_on_terminate(int tick_attempts = 10) {
    step(1);
    // Call this repeated to give the sim time to have a poke propagate to the
    // bridge
    for (int i = 0; i < tick_attempts; i++) {
      terminator->tick();
    }
    expect(terminator->terminate(),
           "Termination bridge correctly calls for termination.");
  }

  void run_test() override {
    // Check that the termination bridge doesn't call for teardown before reset
    // is asserted
    poke("reset", 0);
    poke("io_globalResetCondition", 1);
    poke("io_shouldBeTrue", 0);
    step_assume_continue(10);

    // Check that the termination bridge doesn't call for teardown while the
    // local reset is asserted
    poke("reset", 1);
    poke("io_globalResetCondition", 0);
    step_assume_continue(10);

    // Now proceed with regularly scheduled content
    poke("reset", 0);
    poke("io_shouldBeTrue", 1);
    step_assume_continue(10);

    poke("io_shouldBeTrue", 0);
    step_once_and_wait_on_terminate();

    expect(terminator->cycle_count() == expected_cycle_at_bridge,
           "Termination bridge provides correct exit cycle");
    expect(terminator->exit_code() == 1,
           "Termination bridge returns non-zero when used in assert mode");
  };
};

TEST_MAIN(TestTerminationModuleAssert)
