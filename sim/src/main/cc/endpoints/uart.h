#ifndef __UART_H
#define __UART_H

#include "serial.h"
#include <signal.h>

class uart_t: public endpoint_t
{
    public:
        uart_t(simif_t* sim, AddressMap addr_map, char* slot, char subslot);
        ~uart_t();
        void send();
        void recv();
        virtual void tick();
        virtual bool done() { return read("done"); }
        bool stall() { return read("stall"); }

    private:
        serial_data_t<char> data;
        FILE * stdin_file;
        int    stdin_desc;
        FILE * stdout_file;
        int    stdout_desc;
};

#endif // __UART_H
