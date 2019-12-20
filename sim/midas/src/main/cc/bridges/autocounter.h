#ifndef __AUTOCOUNTER_H
#define __AUTOCOUNTER_H

#include "bridges/bridge_driver.h"
#include "bridges/address_map.h"
#include <vector>
#include <fstream>

// TODO: get this automatically
#define NUM_CORES 1

#ifdef AUTOCOUNTERBRIDGEMODULE_struct_guard
class autocounter_t: public bridge_driver_t {
    public:
        autocounter_t(simif_t *sim, std::vector<std::string> &args,
        AUTOCOUNTERBRIDGEMODULE_struct * mmio_addrs, AddressMap addr_map, int autocounterno);
        ~autocounter_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }
        virtual void finish();

    private:
        AUTOCOUNTERBRIDGEMODULE_struct * mmio_addrs;
        AddressMap addr_map;
        simif_t* sim;
        uint64_t cur_cycle;
        uint64_t readrate;
        std::string autocounter_filename;
        std::ofstream autocounter_file;
};
#endif // AUTOCOUNTERWIDGET_struct_guard

#endif // __AUROCOUNTER_H
