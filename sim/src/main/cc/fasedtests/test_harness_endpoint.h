//See LICENSE for license details.

#ifndef __TEST_HARNESS_ENDPOINT_H
#define __TEST_HARNESS_ENDPOINT_H

#include "endpoints/endpoint.h"

class test_harness_endpoint_t: public endpoint_t
{
    private:
        int error = 0;
        bool done = false;
        simif_t * sim;

    public:
        test_harness_endpoint_t(simif_t* sim, const std::vector<std::string>& args): endpoint_t(sim), sim(sim) {};
        virtual ~test_harness_endpoint_t() {};
        virtual void init() {};
        virtual void tick();
        virtual bool terminate() { return done || error != 0; };
        virtual int exit_code() { return error; };
};

#endif // __TEST_HARNESS_ENDPOINT_H
