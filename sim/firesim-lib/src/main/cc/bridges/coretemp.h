//See LICENSE for license details
#ifndef __CORETEMP_H
#define __CORETEMP_H

#include "bridges/bridge_driver.h"
#include <fstream>

#ifdef  CORETEMPERATUREBRIDGEMODULE_struct_guard
class coretemp_t: public bridge_driver_t
{
    public:
        coretemp_t(simif_t* sim, CORETEMPERATUREBRIDGEMODULE_struct * mmio_addrs, std::vector<std::string> &args);
        ~coretemp_t(){};
        virtual void tick();
        virtual void init();
        virtual void finish() {};
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }
    private:
        CORETEMPERATUREBRIDGEMODULE_struct * mmio_addrs;
        double ambient_temp = 20.0;
        double last_temp = ambient_temp;
        double time_scale = 0.5;
        //double time_scale = 10;
        double dynamic_coefficient = 1.5;
        double static_coefficient = 1.0;
        double cooling_coefficient = time_scale * 1e-8;
        double heating_coefficient = time_scale * 5e-8;

        // THese match the counter width and will roll over
        uint32_t update_interval = 100000;
        uint32_t last_insns_ret = 0;
        uint32_t last_cycle_count = 0;
        uint32_t last_local_cycle_count = 0;

        uint64_t total_insns_ret = 0;
        uint64_t total_cycle_count = 0;
        uint64_t total_local_cycle_count = 0;

        std::ofstream output_file;
};
#endif // CORETEMPERATUREBRIDGEMODULE_struct_guard

#endif // __CORETEMP_H
