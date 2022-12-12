// See LICENSE for license details.

#include "TestHarness.h"

// This test is not active and cannot be compiled.
#error "Test not active"

class TestPointerChaser : public TestHarness {
public:
  TestPointerChaser(const std::vector<std::string> &args) : TestHarness(args) {
    max_cycles = 20000L;
    mpz_inits(address, result, NULL);
    mpz_set_ui(address, 64L);
    mpz_set_ui(result, 1176L);
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg : args) {
      if (arg.find("+max-cycles=") == 0) {
        max_cycles = atoi(arg.c_str() + 12);
      }
      if (arg.find("+address=") == 0) {
        mpz_set_ui(address, atoll(arg.c_str() + 9));
      }
      if (arg.find("+result=") == 0) {
        mpz_set_ui(result, atoll(arg.c_str() + 9));
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
    for (auto e : fpga_models) {
      e->init();
    }
    target_reset();
    int current_cycle = 0;
    poke(io_startAddr_bits, address);
    poke(io_startAddr_valid, 1);
    while (!peek(io_startAddr_ready)) {
      step(1);
    }
    poke(io_startAddr_valid, 0);
    poke(io_result_ready, 0);
    do {
      step(1, false);
      for (auto e : bridges) {
        e->tick();
      }
    } while (!peek(io_result_valid) && cycles() < max_cycles);
    expect(io_result_bits, result);
  }

private:
  std::vector<endpoint_t *> bridges;
  std::vector<FpgaModel *> fpga_models;
  uint64_t max_cycles;
  mpz_t address;
  mpz_t result; // 64 bit
};

TEST_MAIN(TestPointerChaser)
