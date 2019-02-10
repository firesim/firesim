#ifndef __UART_H
#define __UART_H

// #include "serial.h"
#include <signal.h>
#include "endpoints/endpoint.h"
#include "fesvr/firesim_fesvr.h"

// temp added from serial.h
template<class T>
struct serial_data_t {
    struct {
        T bits;
        bool valid;
        bool ready;
        bool fire() { return valid && ready; }
    } in;
    struct {
        T bits;
        bool ready;
        bool valid;
        bool fire() { return valid && ready; }
    } out;
};

#ifdef UARTWIDGET_struct_guard
class uart_t: public endpoint_t
{
    public:
        uart_t(simif_t* sim, UARTWIDGET_struct * mmio_addrs, int uartno);
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
        int inputfd;
        int outputfd;
        int loggingfd;
};
#endif // UARTWIDGET_struct_guard

#endif // __UART_H
