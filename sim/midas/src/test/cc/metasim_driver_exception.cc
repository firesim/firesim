// See LICENSE for license details.
// Run with the generated GCD target through the real pthread/DPI harness.
#include "TestHarness.h"
#include <algorithm>
#include <stdexcept>

class TestDriverException final : public TestHarness {
public:
  TestDriverException(widget_registry_t &registry,
                      const std::vector<std::string> &args,
                      std::string_view target_name)
      : TestHarness(registry, args, target_name),
        nonstandard(std::find(args.begin(), args.end(), "+throw-nonstandard") !=
                    args.end()) {}
  void run_test() override {
    target_reset();
    if (nonstandard)
      throw 42;
    throw std::runtime_error("intentional driver exception regression");
  }

private:
  const bool nonstandard;
};
TEST_MAIN(TestDriverException)
