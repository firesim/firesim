//See LICENSE for license details
#ifndef __UART_H
#define __UART_H

#include "serial.h"
#include <signal.h>

// The definition of the primary constructor argument for a bridge is generated
// by Golden Gate at compile time _iff_ the bridge is instantiated in the
// target. As a result, all bridge driver definitions conditionally remove
// their sources if the constructor class has been defined (the
// <cname>_struct_guard macros are generated along side the class definition.)
//
// The name of this class and its guards are always BridgeModule class name, in
// all-caps, suffixed with "_struct" and "_struct_guard" respectively.

#ifdef UARTBRIDGEMODULE_struct_guard
class uart_t: public bridge_driver_t
{
    public:
        uart_t(simif_t* sim, UARTBRIDGEMODULE_struct * mmio_addrs, int uartno);
        ~uart_t();
        virtual void tick();
        // Our UART bridge's initialzation and teardown procedures don't
        // require interaction with the FPGA (i.e., MMIO), and so we don't need
        // to define init and finish methods (we can do everything in the
        // ctor/dtor)
        virtual void init() {};
        virtual void finish() {};
        // Our UART bridge never calls for the simulation to terminate
        virtual bool terminate() { return false; }
        // ... and thus, never returns a non-zero exit code
        virtual int exit_code() { return 0; }

    private:
        UARTBRIDGEMODULE_struct * mmio_addrs;
        serial_data_t<char> data;
        int inputfd;
        int outputfd;
        int loggingfd;
        void send();
        void recv();
};
#endif // UARTBRIDGEMODULE_struct_guard

#endif // __UART_H
