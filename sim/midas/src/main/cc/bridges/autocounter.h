#ifndef __AUTOCOUNTER_H
#define __AUTOCOUNTER_H

#include "bridges/bridge_driver.h"
#include "bridges/address_map.h"
#include "bridges/clock_info.h"
#include <vector>
#include <fstream>

// This will need to be manually incremented by descretion.
constexpr int autocounter_csv_format_version = 1;

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
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_count, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_types, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_widths, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _accumulator_widths, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_addr_hi, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_addr_lo, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_descriptions, \
        AUTOCOUNTERBRIDGEMODULE_ ## IDX ## _event_labels, \
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
                      const uint32_t event_count,
                      const char* const* event_types,
                      const uint32_t* event_widths,
                      const uint32_t* accumulator_widths,
                      const uint32_t* event_addr_hi,
                      const uint32_t* event_addr_lo,
                      const char* const* event_msgs,
                      const char* const* event_labels,
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
        const uint32_t event_count;
        std::vector<std::string> event_types;
        std::vector<uint32_t> event_widths;
        std::vector<uint32_t> accumulator_widths;
        std::vector<uint32_t> event_addr_hi;
        std::vector<uint32_t> event_addr_lo;
        std::vector<std::string> event_msgs;
        std::vector<std::string> event_labels;
        ClockInfo clock_info;

        uint64_t cur_cycle_base_clock = 0;
        uint64_t readrate;
        uint64_t readrate_base_clock;
        std::string autocounter_filename;
        std::ofstream autocounter_file;

        // Pulls a single sample from the Bridge, if available.
        // Returns true if a sample was read
        bool drain_sample();

        // Writes event autocounter metadata to the first lines of the output csv.
        void emit_autocounter_header();
};
#endif // AUTOCOUNTERWIDGET_struct_guard

#endif // __AUROCOUNTER_H
