// See LICENSE for license details.

#include "TestHarness.h"
#include "bridges/token_hashers.h"

#include <iomanip>
#include <iostream>

#define HEX32_STRING(n)                                                        \
  std::setfill('0') << std::setw(8) << std::hex << (n) << std::dec

/**
 * @brief Token Hashers Bridge Driver Test
 *
 * A simple loopback is used to check the values
 * fed into the hashers
 */
class TestTokenHashersModule : public TestHarness {
private:
  token_hashers_t &hasher = get_bridge<token_hashers_t>();

public:
using TestHarness::TestHarness;

  // TestTokenHashersModule(const std::vector<std::string> &args, simif_t &simif)
  //     : TestHarness(args, simif), hasher(get_bridge<token_hashers_t>())

  // {
    
  // }

  /**
   * Search through the signals recorded by token_hashers. Save the indices
   * we need.
   */
  void signal_search() {
    auto get_one_idx = [&](const std::string &name, size_t &found_idx) {
      const auto find_idx = hasher.search("PeekPokeBridgeModule", name);
      assert(find_idx.size() == 1 &&
             "Hasher reports multiple signals found, expecting only one");
      found_idx = find_idx[0];
    };

    for (size_t i = 0; i < count; i++) {
      get_one_idx(names[i], hash_idx[i]);
    }
  }

  /**
   * The first loop operates the DUT and injects/collects values. The second
   * iterates over the captured data, calculates expected values, and asserts
   */
  void run_test() override {
    signal_search();

    // set the parameters of the hashers inside the DUT
    hasher.set_params(0, 0);

    target_reset();

    // the first loop writes values into the test
    // we assert that the DUT is looping back
    // assertions are made
    for (uint32_t i = 0; i < loops; i++) {

      const uint32_t writeValue = 0xf000 | i;
      poke("io_writeValue", writeValue);
      const uint32_t readValue = peek("io_readValue");
      const uint32_t readValueFlipped = peek("io_readValueFlipped");

      // if these fail, it only means the test is invalid, not that Token
      // Hashers are wrong
      assert(readValue == writeValue);
      assert(readValueFlipped == ~writeValue);

      // std::cout << "step " << i << " wrote " << HEX32_STRING(writeValue) << "
      // read: " << HEX32_STRING(readValue) << "  " <<
      // HEX32_STRING(readValueFlipped) << "\n";
      step(1);
    }

    // one final step
    step(1);

    // loads all the saved data from all of the hashers in the project
    // this is destructive for the DUT, but the values are cached inside the
    // hasher C++ object
    hasher.load_cache();

    // returns the hashes that were read out
    auto all_hashes = hasher.cached_get();

    // loop over saved hashes that the DUT produced
    // as we go, inject the same values into a software version of
    // xorhash32, and assert for correctness
    for (int i = 0; i < loops; i++) {
      const uint32_t writeValue = 0xf000 | i;

      const uint32_t e0 = expected[0].next(writeValue);
      const uint32_t e1 = expected[1].next(writeValue);
      const uint32_t e2 = expected[2].next(~writeValue);

      assert(all_hashes[hash_idx[0]][skip_clocks + i] == e0 &&
             "Readback incorrect hash");
      assert(all_hashes[hash_idx[1]][skip_clocks + i] == e1 &&
             "Readback incorrect hash");
      assert(all_hashes[hash_idx[2]][skip_clocks + i] == e2 &&
             "Readback incorrect hash");
    }
  }

private:
  constexpr static size_t count = 3;
  constexpr static std::array<const char *, count> names = {
      "io_writeValue", "io_readValue", "io_readValueFlipped"};
  std::array<size_t, count> hash_idx;
  std::array<XORHash32, count> expected;
  constexpr static const uint32_t loops = 64;
  constexpr static size_t skip_clocks = 6;
};

TEST_MAIN(TestTokenHashersModule)
