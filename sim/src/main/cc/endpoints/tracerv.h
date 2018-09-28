#ifndef __TRACERV_H
#define __TRACERV_H

#include "endpoints/endpoint.h"

class tracerv_t: public endpoint_t
{
    public:
        tracerv_t(simif_t *sim, char *tracefile);
        ~tracerv_t();

        virtual void init();
        virtual void tick();
	virtual bool terminate() { return false; }
	virtual int exit_code() { return 0; }

    private:
        simif_t* sim;
        FILE * tracefile;
};

#endif // __TRACERV_H
