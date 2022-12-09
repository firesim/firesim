// See LICENSE for license details.

#include "TestHarness.h"

#include <iomanip>
#include <iostream>
#include <string_view>

/**
 * @brief PlusArgs Bridge Driver Test
 *
 * This test parses `+plusargs_test_key` (given by TutorialSuite.scala)
 * and asserts for the correct values
 */
class TestPlusArgsModule : public TestHarness {
private:
  /**
   * Find the `+plusargs_test_key` and set the appropriate member variables
   * All macros should be passed to this constructor.
   *
   * The value is checked to reject any runtime values that are too big for the
   * @param [in] args The argv as a vector
   */
  void parse_key(const std::vector<std::string> &args) {
    constexpr std::string_view find_key = "+plusargs_test_key=";

    for (const auto &arg : args) {
      if (arg.find(find_key) == 0) {
        const std::string key_string = arg.substr(find_key.length());
        found_key = true;
        test_key = std::stoi(key_string);
        break;
      }
    }
  }

public:
  std::unique_ptr<plusargs_t> plusargsinator;
  void add_bridge_driver(plusargs_t *bridge) override {
    assert(!plusargsinator && "multiple bridges registered");
    plusargsinator.reset(bridge);
  }

  /**
   * Constructor.
   *
   * @param [in] args The argument list from main
   */
  TestPlusArgsModule(const std::vector<std::string> &args, simif_t *simif)
      : TestHarness(args, simif) {
    parse_key(args);
  }

  /**
   * Validate function, this holds all of the assertions
   *
   * The correct assertion is made based on `test_key` (as driven by
   * `+plusargs_test_key=`). If no test key is provided, this function does
   * nothing. This check is required due to the way that TestSuiteCommon.scala
   * will always instantiate and run a test with no PlusArgs.
   */
  void validate() {
    if (!found_key) {
      return; // bail if no key was passed. run no assertions
    }

    mpz_t e0; // expected
    mpz_init(e0);

    switch (test_key) {
    case 0x00:
      mpz_set_str(e0, "f0123456789abcdef", 16);
      break;
    case 0x01:
      mpz_set_str(e0, "3", 16);
      break;
    case 0x02:
      mpz_set_str(e0, "f00000000", 16);
      break;
    case 0x03:
      mpz_set_str(e0, "f0000000000000000", 16);
      break;
    case 0x04:
      // value passed from scala is too large, but would truncate to this value
      // scala expects the test to fail, as C++ should exit before this line
      // runs
      mpz_set_str(e0, "f0000000000000000", 16);
      break;
    case 0x10:
      mpz_set_str(e0, "4", 16);
      break;
    case 0x11:
      mpz_set_str(e0, "1eadbeef", 16);
      break;

    default:
    case -1:
      std::cerr << "unknown test_key " << test_key << "\n";
      exit(1);
      break;
    }

    expect(io_gotPlusArgValue, e0);

    mpz_clear(e0);
  }

  /**
   * Run. Check our assertions before the first step, as well as 7 more times.
   * These extra assertion make sure that the value does not change or glitch.
   */
  void run_test() override {

    if (!found_key) {
      std::cout << "No test key found, will not assert\n";
    }

    plusargsinator->init();
    target_reset();

    for (int i = 0; i < 8; i++) {
      // validate before first tick and for a few after (b/c of the loop)
      validate();

      step(1);
    }
  }

private:
  bool found_key = false;
  int test_key = -1;
};

TEST_MAIN(TestPlusArgsModule)
