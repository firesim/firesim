//See LICENSE for license details.

#include <memory>

#include "simif.h"
#include "bridges/autocounter.h"

class autocounter_module_t: virtual simif_t
{
    public:
        std::vector<std::unique_ptr<autocounter_t>> autocounter_endpoints;
        autocounter_module_t(int argc, char** argv) {
            AUTOCOUNTERBRIDGEMODULE_0_substruct_create;
            std::vector<std::string> args(argv + 1, argv + argc);
            autocounter_endpoints.push_back(std::unique_ptr<autocounter_t>(new autocounter_t(this,
                 args,
                 AUTOCOUNTERBRIDGEMODULE_0_substruct,
                 AddressMap(AUTOCOUNTERBRIDGEMODULE_0_R_num_registers,
                    (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_0_R_addrs,
                    (const char* const*) AUTOCOUNTERBRIDGEMODULE_0_R_names,
                    AUTOCOUNTERBRIDGEMODULE_0_W_num_registers,
                    (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_0_W_addrs,
                    (const char* const*) AUTOCOUNTERBRIDGEMODULE_0_W_names), 0)));

#ifdef AUTOCOUNTERBRIDGEMODULE_1_PRESENT
            AUTOCOUNTERBRIDGEMODULE_1_substruct_create;
            autocounter_endpoints.push_back(std::unique_ptr<autocounter_t>(new autocounter_t(this,
                 args,
                 AUTOCOUNTERBRIDGEMODULE_1_substruct,
                 AddressMap(AUTOCOUNTERBRIDGEMODULE_1_R_num_registers,
                    (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_1_R_addrs,
                    (const char* const*) AUTOCOUNTERBRIDGEMODULE_1_R_names,
                    AUTOCOUNTERBRIDGEMODULE_1_W_num_registers,
                    (const unsigned int*) AUTOCOUNTERBRIDGEMODULE_1_W_addrs,
                    (const char* const*) AUTOCOUNTERBRIDGEMODULE_1_W_names), 1)));
#endif
        };
        void run_and_collect(int cycles) {
            step(cycles, false);
            while (!done()) {
                for (auto &autocounter_endpoint: autocounter_endpoints) {
                    autocounter_endpoint->tick();
                }
            }
            for (auto &autocounter_endpoint: autocounter_endpoints) {
                autocounter_endpoint->finish();
            }
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
