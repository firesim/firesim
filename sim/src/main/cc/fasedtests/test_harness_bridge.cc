//See LICENSE for license details.

#include "test_harness_bridge.h"

test_harness_bridge_t::test_harness_bridge_t(
  simif_t* sim,
  AddressMap addr_map, // This matches the addr map pass to the FASED timing model
  const std::vector<std::string>& args) : bridge_driver_t(sim), sim(sim), addr_map(addr_map) {


  for (auto &arg: args) {
    // Record all uarch events we want to validate
    if(arg.find("+expect_") == 0) {
      auto sub_arg = std::string(arg.c_str() + 8);
      size_t delimit_idx = sub_arg.find_first_of("=");
      std::string key = sub_arg.substr(0, delimit_idx).c_str();
      int value = std::stoi(sub_arg.substr(delimit_idx+1).c_str());
      expected_uarchevent_values[key] = value;
    }
  }
}

// This periodically peeks a done bit on the DUT. After it's been asserted,
// it then reads uarch event counts from the FASED instance and compares them against
// expected values
void test_harness_bridge_t::tick(){
  this->done = sim->sample_value(done); // use a non-blocking sample since this signal is monotonic
  if(done) {
    this->error = 0;
    // Iterate through all uarch values we want to validate
    for (auto& pair: expected_uarchevent_values) {
      auto actual_value = read(addr_map.r_addr(pair.first));
      // If one doesn't match, croak
      if (actual_value != pair.second) {
        error = 1;
        fprintf(stderr, "FASED Test Harness -- %s did not match: Measured %d, Expected %d\n",
          pair.first.c_str(), actual_value, pair.second);
      }
    }
  }
}
