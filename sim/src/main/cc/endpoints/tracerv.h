#ifndef __TRACERV_H
#define __TRACERV_H

#include "endpoints/endpoint.h"

class tracerv_t: public endpoint_t
{
    public:
        tracerv_t(simif_t *sim, char *tracefile,
	          uint64_t start_cycle, uint64_t end_cycle);
        ~tracerv_t();

        virtual void init();
        virtual void tick();
	virtual bool terminate() { return false; }
	virtual int exit_code() { return 0; }

    private:
        simif_t* sim;
        FILE * tracefile;
	uint64_t start_cycle, end_cycle, cur_cycle;
};

#endif // __TRACERV_H
