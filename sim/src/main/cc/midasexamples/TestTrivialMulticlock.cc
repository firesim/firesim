// See LICENSE for license details.

#include "TestHarness.h"

class MulticlockChecker {
public:
  TestHarness *harness;
  std::string_view field_address;
  int numerator, denominator;
  int cycle = 0;
  uint32_t expected_value;
  uint32_t fast_domain_reg = 0;
  uint32_t slow_domain_reg = 0;
  uint32_t fast_domain_reg_out = 0;

  MulticlockChecker(TestHarness *harness,
                    std::string_view field_address,
                    int numerator,
                    int denominator)
      : harness(harness), field_address(field_address), numerator(numerator),
        denominator(denominator) {}

  void expect_and_update(uint64_t poked_value) {
    harness->expect(field_address, fast_domain_reg_out);
    fast_domain_reg_out = slow_domain_reg;
    int slow_clock_cycle = (cycle * numerator) / denominator;
    if (cycle == 0 ||
        (slow_clock_cycle > (((cycle - 1) * numerator) / denominator))) {
      // TODO: Handle the case where numerator * cycle is not a multiple of the
      // division
      // if (((cycle * numerator) % denominator) != 0) {
      //  fast_domain_reg_out = slow_domain_reg;
      //  slow_domain_reg = poked_value;
      //} else {
      slow_domain_reg = fast_domain_reg;
      //}
    }
    fast_domain_reg = poked_value;
    cycle++;
  };
};

class TestTrivialMulticlock final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    uint64_t limit = 256;
    std::vector<MulticlockChecker *> checkers;
    checkers.push_back(new MulticlockChecker(this, "halfOut", 1, 2));
    checkers.push_back(new MulticlockChecker(this, "thirdOut", 1, 3));
    // Resolve bug in PeekPoke Bridge
    // checkers.push_back(new MulticlockChecker(this, threeSeventhsOut, 3, 7));

    for (int i = 1; i < 1024; i++) {
      for (auto checker : checkers)
        checker->expect_and_update(i);
      poke("in", i);
      step(1);
    }
  }
};

TEST_MAIN(TestTrivialMulticlock)
