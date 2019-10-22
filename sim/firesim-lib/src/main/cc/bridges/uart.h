//See LICENSE for license details
#ifndef __UART_H
#define __UART_H

#include "serial.h"
#include <signal.h>

#ifdef UARTBRIDGEMODULE_struct_guard
class uart_t: public bridge_driver_t
{
    public:
        uart_t(simif_t* sim, UARTBRIDGEMODULE_struct * mmio_addrs, int uartno);
        ~uart_t();
        void send();
        void recv();
        virtual void init() {};
        virtual void tick();
        virtual void finish() {};
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }

    private:
        UARTBRIDGEMODULE_struct * mmio_addrs;
        serial_data_t<char> data;
        int inputfd;
        int outputfd;
        int loggingfd;
};
#endif // UARTBRIDGEMODULE_struct_guard

#endif // __UART_H
