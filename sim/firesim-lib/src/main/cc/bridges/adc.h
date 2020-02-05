//See LICENSE for license details

#ifndef __ADC_H
#define __ADC_H

#include "bridges/bridge_driver.h"
#include <vector>

#ifdef ADCBRIDGEMODULE_struct_guard
class adc_t: public bridge_driver_t
{
    public:
        adc_t(simif_t* sim, std::vector<std::string> &args,
            ADCBRIDGEMODULE_struct *addrs, uint32_t (*signal_func)(uint64_t) );
        ~adc_t();

        virtual void init();
        virtual void tick();
        virtual bool terminate() { return false; };
        virtual int exit_code() { return 0; }
        virtual void finish() {};

    private:
        simif_t* sim;
        ADCBRIDGEMODULE_struct *mmio_addrs;
        uint64_t sampling_freq;
        uint32_t adc_bits;
        char * pcis_write_bufs[2];
        uint32_t signal_func (uint64_t);
        uint64_t timeelapsed_cycles = 0;
        int currentround = 0;
        long dma_addr;
};
#endif // ADCBRIDGEMODULE_struct_guard

#endif // __ADC_H
