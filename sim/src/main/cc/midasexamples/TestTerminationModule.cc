// See LICENSE for license details.

#include "TestHarness.h"

#include <iostream>
#include <string.h>

class TestTerminationModule final : public TestHarness {
public:
  using TestHarness::TestHarness;

  std::unique_ptr<termination_t> terminator;
  void add_bridge_driver(termination_t *bridge) override {
    assert(!terminator && "multiple bridges registered");
    terminator.reset(bridge);
  }

  void run_test() override {
    poke("reset", 1);
    int lv_validinCycle = 0;
    int validinCycle = 0;
    int lv_msginCycle = 0;
    int msginCycle = 0;
    int msgid = 0;
    int reset_length = 1;
    std::string failure_msg_list[3] = {"success 1", "success 2", "failure 3"};
    int failure_cond_list[3] = {0, 0, 1};
    validinCycle = random() % 100;
    msginCycle = random() % 100;
    int termination_code = random() % 8;
    lv_validinCycle = lv_validinCycle + validinCycle;
    lv_msginCycle = lv_msginCycle + msginCycle;
    poke(io_validInCycle, lv_validinCycle);
    poke(io_msgInCycle, lv_msginCycle);
    poke(io_doneErrCode, termination_code);
    step(reset_length);
    poke("reset", 0);
    if (termination_code % 2 != 0) {
      msgid = 0;
    } else if (termination_code % 4 != 0) {
      msgid = 1;
    } else {
      msgid = 2;
    }
    step(lv_validinCycle + 2, false);
    while (!terminator->terminate()) {
      terminator->tick();
    }
    int str_match = terminator->exit_message() == failure_msg_list[msgid];
    expect(terminator->cycle_count() == (lv_validinCycle + reset_length),
           "Code Exits at precise time");
    expect(terminator->exit_code() == failure_cond_list[msgid],
           "Error Code Verified");
    expect(str_match, "Error Message Verified");
  };
};

TEST_MAIN(TestTerminationModule)
