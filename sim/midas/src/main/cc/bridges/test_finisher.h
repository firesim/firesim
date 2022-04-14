
#ifndef __TEST_FINISHER_H
#define __TEST_FINISHER_H
//See LICENSE for license details.

#ifdef TESTFINISHERBRIDGEMODULE_struct_guard

#include "bridge_driver.h"

class test_finisher_t: public bridge_driver_t
{
    public:
        test_finisher_t(simif_t* sim, TESTFINISHERBRIDGEMODULE_struct * mmio_addrs);
				~test_finisher_t();
        virtual void init() {};
        virtual void tick();
        virtual void finish() {};
        virtual bool terminate() { return test_done; };
        virtual int exit_code() { return test_done ? 1 : 0; };
    private:
        TESTFINISHERBRIDGEMODULE_struct * mmio_addrs;
				bool test_done = false;
#endif // TestFinisherBridgeModule_struct_guard
#endif //__TEST_FINISHER_H
