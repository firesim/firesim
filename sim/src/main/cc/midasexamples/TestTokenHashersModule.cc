// See LICENSE for license details.

#include "TestHarness.h"
#include "bridges/plusargs.h"
#include "bridges/token_hashers.h"

#include <iomanip>
#include <iostream>
#include <string_view>

#define HEX32_STRING(n) std::setfill('0') << std::setw(8) << std::hex << (n) << std::dec

/**
 * @brief Token Hashers Bridge Driver Test
 *
 * This test copies the cramework from PlusArgsModule.h
 * parses `+plusargs_test_key` (given by TutorialSuite.scala)
 * and asserts for the correct values
 */
class TestTokenHashersModule : public TestHarness {
private:
  token_hashers_t &hasher;
public:

  /**
   * Constructor.
   *
   * @param [in] argc The standard argc from main()
   * @param [in] argv The standard argv from main()
   */
  TestTokenHashersModule(const std::vector<std::string> &args, simif_t &simif)
      : TestHarness(args, simif),
      hasher(get_bridge<token_hashers_t>()) 
      
      {

    token_hashers_t &x = get_bridge<token_hashers_t>();
    x.info();
    

  }

/**
 * search through the signals recorded by token_hashers 
*/
  void signal_search() {

    auto get_one_idx = [&](const std::string &name, size_t &found_idx) {
      auto find_idx = hasher.search("PeekPokeBridgeModule", name);
      assert(find_idx.size() == 1 && "Hasher reports multiple signals found, expecting only one");
      found_idx = find_idx[0];
      std::cout << name << " was found at idx: "  << found_idx;
    };

    get_one_idx("io_writeValue", write_idx);
    get_one_idx("io_readValue", read_idx);
    get_one_idx("io_readValueFlipped", flipped_idx);
  }

  /**
   * Run. Check our assertions before the first step, as well as 7 more times.
   * These extra assertion make sure that the value does not change or glitch.
   */
  void run_test() override {


    hasher.set_params(0,0);
    // const int loops = choose_params();
    const int loops = 16;


    // plusargsinator.init();
    target_reset();
    for (int i = 0; i < loops; i++) {
      // validate before first tick and for a few after (b/c of the loop)
      // validate();

      uint32_t writeValue = 0xf000 | i;
      poke("io_writeValue", writeValue);
      uint32_t readValue = peek("io_readValue");
      uint32_t readValueFlipped = peek("io_readValueFlipped");

      std::cout << "step " << i << " wrote " << HEX32_STRING(writeValue) << " read: " << HEX32_STRING(readValue) << "  " << HEX32_STRING(readValueFlipped) << "\n";

      step(1);
    }

    // hasher.get();
    // hasher.print();
    std::cout << hasher.get_csv_string();

    // hasher.write_csv_file("test-run.csv");
  }

private:
  size_t write_idx;
  size_t read_idx;
  size_t flipped_idx;
};

TEST_MAIN(TestTokenHashersModule)
