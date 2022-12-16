// See LICENSE for license details.

#include <cstdio>
#include <cstdlib>
#include <map>
#include <utility>

#include "TestHarness.h"

constexpr size_t multireg_n_copies = 4;

static const std::string_view multireg_input_ios[multireg_n_copies] = {
    "io_pipeIOs_0_i", "io_pipeIOs_1_i", "io_pipeIOs_2_i", "io_pipeIOs_3_i"};

static const std::string_view multireg_output_ios[multireg_n_copies] = {
    "io_pipeIOs_0_o", "io_pipeIOs_1_o", "io_pipeIOs_2_o", "io_pipeIOs_3_o"};

static const uint32_t inputs[] = {45, 9, 11, 13, 99, 2, 8, 15, 16, 12, 1};

class TestMultiReg : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    target_reset();
    for (int i = 0; i < sizeof(inputs) / sizeof(inputs[0]); i++) {
      for (int j = 0; j < multireg_n_copies; j++) {
        poke(multireg_input_ios[j], inputs[i] + j);
        if (i > 0)
          expect(multireg_output_ios[j], inputs[i - 1] + j);
        step(1);
      }
    }
  }
};

TEST_MAIN(TestMultiReg)
