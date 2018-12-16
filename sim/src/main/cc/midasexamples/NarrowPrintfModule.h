//See LICENSE for license details.

#include "PrintfModule.h"
class NarrowPrintfModule_t: public print_module_t, virtual simif_t
{
public:
    NarrowPrintfModule_t(int argc, char** argv): print_module_t(argc, argv) {};
    virtual void run() {
        print_endpoint->init();
        poke(reset, 1);
        poke(enable, 0);
        step(1);
        poke(enable, 1);
        poke(reset, 0);
        step(4);
        // Test idle-cycle rollover
        poke(enable, 0);
        step(256);
        poke(enable, 1);
        run_and_collect_prints(256);
    };
};
