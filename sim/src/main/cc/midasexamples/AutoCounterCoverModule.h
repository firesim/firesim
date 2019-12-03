//See LICENSE for license details.

#include <memory>

#include "simif.h"
#include "AutoCounterModule.h"

#ifdef DESIGNNAME_AutoCounterCoverModule
class AutoCounterCoverModule_t: public autocounter_module_t, virtual simif_t
{
public:
    AutoCounterCoverModule_t(int argc, char** argv): autocounter_module_t(argc, argv) {};
    virtual void run() {
        autocounter_endpoint->init();
        poke(reset, 1);
        poke(io_a, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(io_a, 1);
        run_and_collect(3000);
    };
};
#endif //DESIGNNAME_AutoCounterCoverModule
