#ifndef __UART_H
#define __UART_H

#include "serial.h"
#include <signal.h>

#ifdef UARTWIDGET_struct_guard
class uart_t: public endpoint_t
{
    public:
        uart_t(simif_t* sim, UARTWIDGET_struct * mmio_addrs);
        ~uart_t();
        void send();
        void recv();
        virtual void init() {};
        virtual void tick();
        virtual bool terminate() { return false; }
        virtual int exit_code() { return 0; }

    private:
        UARTWIDGET_struct * mmio_addrs;
        serial_data_t<char> data;
};
#endif // UARTWIDGET_struct_guard

#endif // __UART_H
