#ifndef __SERIAL_H
#define __SERIAL_H

#include "endpoints/endpoint.h"
#include "fesvr/firesim_fesvr.h"

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

class serial_t: public endpoint_t
{
    public:
        serial_t(simif_t* sim, firesim_fesvr_t* fesvr);
        void send();
        void recv();
        void work();
        virtual void tick() { }
        virtual bool done() { return read(SERIALWIDGET_0(done)); }
        virtual bool stall() { return false; }

    private:
        serial_data_t<uint32_t> data;
        firesim_fesvr_t* fesvr;
};

#endif // __SERIAL_H
