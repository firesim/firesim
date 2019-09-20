#ifndef __AUTOCOUNTER_H
#define __AUTOCOUNTER_H

#include "endpoints/endpoint.h"
#include <vector>
#include <fstream>

// TODO: get this automatically
#define NUM_CORES 1

#ifdef AUTOCOUNTERWIDGET_struct_guardclass autocounter_t: public endpoint_t
class autocounter_t: public endpoint_t
    public:
        autocounter_t(simif_t *sim, std::vector<std::string> &args,
        AUTOCOUNTERWIDGET_struct * mmio_addrs, AddressMap addr_map);
        ~autocounter_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }
        virtual void finish() {};

    private:
        AUTOCOUNTERWIDGET_struct * mmio_addrs;
        AddressMap addr_map;
        simif_t* sim;
        uint64_t cur_cycle;
        uint64_t readrate;
        uint64_t readrate_count;
        std::string autocounter_filename;
        std::ofstream autocounter_file;
};
#endif // AUTOCOUNTERWIDGET_struct_guard

#endif // __AUROCOUNTER_H
