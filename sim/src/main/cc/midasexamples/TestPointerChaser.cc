// See LICENSE for license details.

#include "TestHarness.h"

class TestPointerChaser : public TestHarness {
public:
  TestPointerChaser(widget_registry_t &registry,
                  const std::vector<std::string> &args,
                  std::string_view target_name)
      : TestHarness(registry, args, target_name) {
    max_cycles = 20000L;
    mpz_inits(address, result, NULL);
    mpz_set_ui(address, 64L);
    mpz_set_ui(result, 1176L);

    constexpr std::string_view max_cycles_key = "+max-cycles=";
    constexpr std::string_view address_key = "+address=";
    constexpr std::string_view result_key = "+result=";
    for (const auto &arg : args) {
      if (arg.find(max_cycles_key) == 0) {
        max_cycles = atoi(arg.substr(max_cycles_key.length()).c_str());
      }
      if (arg.find(address_key) == 0) {
        mpz_set_ui(address, atoll(arg.substr(address_key.length()).c_str()));
      }
      if (arg.find(result_key) == 0) {
        mpz_set_ui(result, atoll(arg.substr(result_key.length()).c_str()));
      }
    }

#ifdef FASEDMEMORYTIMINGMODEL_0_PRESENT
    uint64_t host_mem_offset = 0x00000000LL;
    fpga_models.push_back(new FASEDMemoryTimingModel(
        simif,
        // Casts are required for now since the emitted type can change...
        AddressMap(FASEDMEMORYTIMINGMODEL_0_R_num_registers,
                   (const unsigned int *)FASEDMEMORYTIMINGMODEL_0_R_addrs,
                   (const char *const *)FASEDMEMORYTIMINGMODEL_0_R_names,
                   FASEDMEMORYTIMINGMODEL_0_W_num_registers,
                   (const unsigned int *)FASEDMEMORYTIMINGMODEL_0_W_addrs,
                   (const char *const *)FASEDMEMORYTIMINGMODEL_0_W_names),
        argc,
        argv,
        "memory_stats.csv",
        1L << FASEDMEMORYTIMINGMODEL_0_target_addr_bits,
        host_mem_offset));
#endif
  }

  void run_test() {
    target_reset();
    int current_cycle = 0;
    poke("io_startAddr_bits", address);
    poke("io_startAddr_valid", 1);
    while (!peek("io_startAddr_ready")) {
      step(1);
    }
    poke("io_startAddr_valid", 0);
    poke("io_result_ready", 0);
    do {
      step(1, false);
    } while (!peek("io_result_valid") && cycles() < max_cycles);
    expect("io_result_bits", result);
  }

private:
  uint64_t max_cycles;
  mpz_t address;
  mpz_t result; // 64 bit
};

TEST_MAIN(TestPointerChaser)
