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

class uart_t: public endpoint_t
{
    public:
        uart_t(simif_t* sim);
        void send();
        void recv();
        virtual void tick();
        virtual bool done() { return read(UARTWIDGET_0(done)); }
        bool stall() { return read(UARTWIDGET_0(stall)); }

    private:
        serial_data_t<char> data;
};

#endif // __UART_H
