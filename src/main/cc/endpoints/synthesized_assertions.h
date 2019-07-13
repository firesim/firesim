#ifndef __SYNTHESIZED_ASSERTIONS_H
#define __SYNTHESIZED_ASSERTIONS_H

#ifdef ASSERTWIDGET_struct_guard

#include "endpoint.h"

class synthesized_assertions_t: public endpoint_t
{
    public:
        synthesized_assertions_t(simif_t* sim, ASSERTWIDGET_struct * mmio_addrs);
        ~synthesized_assertions_t();
        virtual void init() {};
        virtual void tick();
        virtual void finish() {};
        void resume(); // Clears any set assertions, and allows the simulation to advance
        virtual bool terminate() { return assert_fired; };
        virtual int exit_code() { return (assert_fired) ? assert_id + 1 : 0; };
    private:
        bool assert_fired = false;
        int assert_id;
        uint64_t assert_cycle;
        ASSERTWIDGET_struct * mmio_addrs;
};

#endif // ASSERTWIDGET_struct_guard

#endif //__SYNTHESIZED_ASSERTIONS_H
