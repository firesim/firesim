//See LICENSE for license details.

#include <memory>

#include "simif.h"
#include "bridges/autocounter.h"

class autocounter_module_t: virtual simif_t
{
    public:
        std::unique_ptr<autocounter_t> autocounter_endpoint;
        autocounter_module_t(int argc, char** argv) {
            AUTOCOUNTERBRIDGEMODULE_0_substruct_create;
            std::vector<std::string> args(argv + 1, argv + argc);
            autocounter_endpoint = std::unique_ptr<autocounter_t>(new autocounter_t(this,
                 args, 
                 AUTOCOUNTERBRIDGEMODULE_0_substruct,
                 AddressMap(AUTOCOUNTERBRIDGEMODULE_0_R_num_registers,
                    (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_0_R_addrs,
                    (const char* const*) AUTOCOUNTERBRIDGEMODULE_0_R_names,
                    AUTOCOUNTERBRIDGEMODULE_0_W_num_registers,
                    (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_0_W_addrs,
                    (const char* const*) AUTOCOUNTERBRIDGEMODULE_0_W_names), 0));
        };
        void run_and_collect(int cycles) {
            step(cycles, false);
            while (!done()) {
                autocounter_endpoint->tick();
            }
            autocounter_endpoint->finish();
        };
};

#ifdef DESIGNNAME_AutoCounterModule
class AutoCounterModule_t: public autocounter_module_t, virtual simif_t
{
public:
    AutoCounterModule_t(int argc, char** argv): autocounter_module_t(argc, argv) {};
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
#endif //DESIGNNAME_AutoCounterModule
