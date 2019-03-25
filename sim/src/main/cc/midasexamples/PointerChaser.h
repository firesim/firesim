//See LICENSE for license details.

#include "simif.h"
#include "endpoints/endpoint.h"
#include "endpoints/fased_memory_timing_model.h"

class PointerChaser_t: virtual simif_t
{
public:
  PointerChaser_t(int argc, char** argv) {
    max_cycles = 20000L;
    mpz_inits(address, result, NULL);
    mpz_set_ui(address, 64L);
    mpz_set_ui(result, 1176L);
    std::vector<std::string> args(argv + 1, argv + argc);
    for (auto &arg: args) {
      if (arg.find("+max-cycles=") == 0) {
        max_cycles = atoi(arg.c_str()+12);
      }
      if (arg.find("+address=") == 0) {
        mpz_set_ui(address, atoll(arg.c_str() + 9));
      }
      if (arg.find("+result=") == 0) {
        mpz_set_ui(result, atoll(arg.c_str() + 9));
      }
    }

#ifdef MEMMODEL_0
    uint64_t host_mem_offset = 0x00000000LL;
    fpga_models.push_back(new FASEDMemoryTimingModel(
        this,
        // Casts are required for now since the emitted type can change...
        AddressMap(MEMMODEL_0_R_num_registers,
                   (const unsigned int*) MEMMODEL_0_R_addrs,
                   (const char* const*) MEMMODEL_0_R_names,
                   MEMMODEL_0_W_num_registers,
                   (const unsigned int*) MEMMODEL_0_W_addrs,
                   (const char* const*) MEMMODEL_0_W_names),
        argc, argv, "memory_stats.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
#endif
  }

  void run() {
    for (auto e: fpga_models) {
      e->init();
    }
    target_reset(0);
    int current_cycle = 0;

    poke(io_startAddr_bits, address);
    poke(io_startAddr_valid, 1);
    do {
      step(1);
    } while (!peek(io_startAddr_ready));
    poke(io_startAddr_valid, 0);
    poke(io_result_ready, 0);
    do {
      step(1, false);
      for (auto e: endpoints) {
        e->tick();
      }
    } while (!peek(io_result_valid) && cycles() < max_cycles);
    expect(io_result_bits, result);
  }
private:
  std::vector<endpoint_t*> endpoints;
  std::vector<FpgaModel*> fpga_models;
  uint64_t max_cycles;
  mpz_t address;
  mpz_t result; // 64 bit
};
