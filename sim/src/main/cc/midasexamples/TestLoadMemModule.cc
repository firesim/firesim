// See LICENSE for license details.

#include "TestHarness.h"

class TestLoadMemModule final : public TestHarness {
public:
  static constexpr unsigned timeout = 128;

  FILE *output = stdout;
  uint32_t n = 128;

  TestLoadMemModule(widget_registry_t &registry,
                    const std::vector<std::string> &args,
                    std::string_view target_name)
      : TestHarness(registry, args, target_name) {
    constexpr std::string_view test_dump_key = "+test-dump-file=";
    constexpr std::string_view n_key = "+n=";

    for (const auto &arg : args) {
      if (arg.find(test_dump_key) == 0) {
        output = fopen(arg.substr(test_dump_key.length()).c_str(), "w");
        continue;
      }
      if (arg.find(n_key) == 0) {
        n = atoi(arg.substr(n_key.length()).c_str());
        continue;
      }
    }
  }

  ~TestLoadMemModule() override {
    if (output != stdout)
      fclose(output);
  }

  void run_test() override {
    // Reset the DUT.
    peek_poke.poke("reset", 1, /*blocking=*/true);
    peek_poke.step(1, /*blocking=*/true);
    peek_poke.poke("reset", 0, /*blocking=*/true);
    peek_poke.step(1, /*blocking=*/true);

    // Run through an address range and print the value.
    for (uint32_t addr = 0; addr < n; ++addr) {
      peek_poke.step(1000, false);
      peek_poke.poke("addr", addr, false);

      // The address change triggers a read. Wait for the
      // done flag to indicate its completion.
      bool done = false;
      for (unsigned i = 0; i < timeout; ++i) {
        if (peek_poke.peek("done", true)) {
          done = true;
          break;
        }
      }
      if (!done) {
        fprintf(stderr, "Cannot read value at %x\n", addr);
        exit(1);
      }

      // Read the value and print it to the output file.
      uint32_t lo = peek_poke.peek("data_lo", true);
      uint32_t hi = peek_poke.peek("data_hi", true);
      fprintf(output, "%08x%08x\n", hi, lo);
    }
  }
};

TEST_MAIN(TestLoadMemModule)
