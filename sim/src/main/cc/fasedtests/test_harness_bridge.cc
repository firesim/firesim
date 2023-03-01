// See LICENSE for license details.

#include "test_harness_bridge.h"
#include "bridges/fased_memory_timing_model.h"

char test_harness_bridge_t::KIND;

test_harness_bridge_t::test_harness_bridge_t(
    simif_t &simif,
    peek_poke_t &peek_poke,
    const std::vector<FASEDMemoryTimingModel *> &models,
    const std::vector<std::string> &args)
    : bridge_driver_t(simif, &KIND), peek_poke(peek_poke), models(models) {

  for (auto &arg : args) {
    // Record all uarch events we want to validate
    if (arg.find("+expect_") == 0) {
      auto sub_arg = std::string(arg.c_str() + 8);
      size_t delimit_idx = sub_arg.find_first_of("=");
      std::string key = sub_arg.substr(0, delimit_idx).c_str();
      int value = std::stoi(sub_arg.substr(delimit_idx + 1).c_str());
      expected_uarchevent_values[key] = value;
    }
  }
}

void test_harness_bridge_t::tick() {
  // use a non-blocking sample since this signal is monotonic
  done = peek_poke.sample_value("done");
  if (!done)
    return;

  // Iterate through all uarch values we want to validate
  for (auto &model : models) {
    for (auto &[key, value] : expected_uarchevent_values) {
      auto actual_value = simif.read(model->get_addr_map().r_addr(key));
      // If one doesn't match, croak
      if (actual_value != value) {
        error = 1;
        fprintf(stderr,
                "FASED Test Harness -- %s did not match: Measured %d, Expected "
                "%d\n",
                key.c_str(),
                actual_value,
                value);
      }
    }
  }
}
