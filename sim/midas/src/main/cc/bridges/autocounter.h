#ifndef __AUTOCOUNTER_H
#define __AUTOCOUNTER_H

#include "bridges/bridge_driver.h"
#include "bridges/address_map.h"
#include "bridges/clock_info.h"
#include <vector>
#include <fstream>

// Bridge Driver Instantiation Template
#define INSTANTIATE_AUTOCOUNTER(FUNC,IDX) \
    AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _substruct_create; \
    FUNC(new autocounter_t( \
        this, \
        args, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _substruct, \
        AddressMap(AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _R_num_registers, \
           (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _R_addrs, \
           (const char* const*) AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _R_names, \
           AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _W_num_registers, \
           (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _W_addrs, \
           (const char* const*) AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _W_names), \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _clock_domain_name, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _clock_multiplier, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _clock_divisor, \
        IDX)); \


#ifdef AUTOCOUNTERBRIDGEMODULE_struct_guard
class autocounter_t: public bridge_driver_t {
    public:
        autocounter_t(simif_t *sim,
                      std::vector<std::string> &args,
                      AUTOCOUNTERBRIDGEMODULE_struct * mmio_addrs,
                      AddressMap addr_map,
                      const char* const  clock_domain_name,
                      const unsigned int clock_multiplier,
                      const unsigned int clock_divisor,
                      int autocounterno);
        ~autocounter_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }
        virtual void finish();

    private:
        simif_t* sim;
        AUTOCOUNTERBRIDGEMODULE_struct * mmio_addrs;
        AddressMap addr_map;
        ClockInfo clock_info;
        uint64_t cur_cycle;
        uint64_t readrate;
        std::string autocounter_filename;
        std::ofstream autocounter_file;

        // Pulls a single sample from the Bridge, if available.
        // Returns true if a sample was read
        bool drain_sample();
};
#endif // AUTOCOUNTERWIDGET_struct_guard

#endif // __AUROCOUNTER_H
