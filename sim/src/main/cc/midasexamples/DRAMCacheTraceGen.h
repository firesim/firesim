
#include "simif.h"
#include "endpoints/endpoint.h"
#include "endpoints/fpga_memory_model.h"
#include "endpoints/synthesized_assertions.h"

#include <stdint.h>

class DRAMCacheTraceGen_t : virtual simif_t
{
  public:
    DRAMCacheTraceGen_t(int argc, char **argv);
    void run();
    bool finished();
  private:
    std::vector<endpoint_t*> endpoints;
    std::vector<FpgaModel*> fpga_models;
    uint64_t max_cycles;
};

DRAMCacheTraceGen_t::DRAMCacheTraceGen_t(int argc, char **argv)
{
    std::vector<std::string> args(argv + 1, argv + argc);
    max_cycles = UINT64_MAX;

    for (auto &arg: args) {
        if (arg.find("+max-cycles=") == 0) {
            max_cycles = atoi(arg.c_str()+12);
        }
    }

    uint64_t host_mem_offset = 0x00000000LL;

#ifdef MEMMODEL_0
    fpga_models.push_back(new FpgaMemoryModel(
        this,
        // Casts are required for now since the emitted type can change...
        AddressMap(MEMMODEL_0_R_num_registers,
                   (const unsigned int*) MEMMODEL_0_R_addrs,
                   (const char* const*) MEMMODEL_0_R_names,
                   MEMMODEL_0_W_num_registers,
                   (const unsigned int*) MEMMODEL_0_W_addrs,
                   (const char* const*) MEMMODEL_0_W_names),
        argc, argv, "memory_stats.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offset += (1ULL << MEMMODEL_0_target_addr_bits);
#endif

#ifdef MEMMODEL_1
    fpga_models.push_back(new FpgaMemoryModel(
        this,
        // Casts are required for now since the emitted type can change...
        AddressMap(MEMMODEL_1_R_num_registers,
                   (const unsigned int*) MEMMODEL_1_R_addrs,
                   (const char* const*) MEMMODEL_1_R_names,
                   MEMMODEL_1_W_num_registers,
                   (const unsigned int*) MEMMODEL_1_W_addrs,
                   (const char* const*) MEMMODEL_1_W_names),
        argc, argv, "memory_stats.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offset += (1ULL << MEMMODEL_1_target_addr_bits);
#endif

#ifdef MEMMODEL_2
    fpga_models.push_back(new FpgaMemoryModel(
        this,
        // Casts are required for now since the emitted type can change...
        AddressMap(MEMMODEL_2_R_num_registers,
                   (const unsigned int*) MEMMODEL_2_R_addrs,
                   (const char* const*) MEMMODEL_2_R_names,
                   MEMMODEL_2_W_num_registers,
                   (const unsigned int*) MEMMODEL_2_W_addrs,
                   (const char* const*) MEMMODEL_2_W_names),
        argc, argv, "memory_stats.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offset += (1ULL << MEMMODEL_2_target_addr_bits);
#endif

#ifdef MEMMODEL_3
    fpga_models.push_back(new FpgaMemoryModel(
        this,
        // Casts are required for now since the emitted type can change...
        AddressMap(MEMMODEL_3_R_num_registers,
                   (const unsigned int*) MEMMODEL_3_R_addrs,
                   (const char* const*) MEMMODEL_3_R_names,
                   MEMMODEL_3_W_num_registers,
                   (const unsigned int*) MEMMODEL_3_W_addrs,
                   (const char* const*) MEMMODEL_3_W_names),
        argc, argv, "memory_stats.csv", 1L << TARGET_MEM_ADDR_BITS, host_mem_offset));
     host_mem_offset += (1ULL << MEMMODEL_3_target_addr_bits);
#endif

#ifdef ASSERTIONWIDGET_struct_guard
    #ifdef ASSERTIONWIDGET_0_PRESENT
    ASSERTIONWIDGET_0_substruct_create;
    endpoints.push_back(
        new synthesized_assertions_t(this, ASSERTIONWIDGET_0_substruct));
    #endif
#endif
}

bool DRAMCacheTraceGen_t::finished()
{
    if (peek(io_success))
        return true;

    for (auto e: endpoints) {
        if (e->terminate())
            return true;
    }

    return false;
}

void DRAMCacheTraceGen_t::run()
{
    for (auto m: fpga_models) {
        m->init();
    }

    for (auto e: endpoints) {
        e->init();
    }

    target_reset(0);

    do {
        step(1, false);
        for (auto e: endpoints) e->tick();
    } while (!finished() && cycles() < max_cycles);

    for (auto m: fpga_models) {
        m->finish();
    }
}
