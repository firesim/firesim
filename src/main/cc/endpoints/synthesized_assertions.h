#ifndef __SYNTHESIZED_ASSERTIONS_H
#define __SYNTHESIZED_ASSERTIONS_H

#include "endpoint.h"

class synthesized_assertions_t: public endpoint_t
{
    public:
        synthesized_assertions_t(simif_t* sim): endpoint_t(sim) {};
        virtual void init() {};
        virtual void tick();
        void resume(); // Clears any set assertions, and allows the simulation to advance
        virtual bool terminate() { return assert_fired; };
        virtual int exit_code() { return assert_id; };

    private:
        bool assert_fired = false;
        int assert_id;
        uint64_t  assert_cycle;
};

#endif //__SYNTHESIZED_ASSERTIONS_H
