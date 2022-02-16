//See LICENSE for license details.

#include <memory>

#include "simif_peek_poke.h"
#include "bridges/autocounter.h"

class autocounter_module_t: public simif_peek_poke_t
{
    public:
        std::vector<autocounter_t *> autocounter_endpoints;
        autocounter_module_t(int argc, char** argv) {
            std::vector<std::string> args(argv + 1, argv + argc);
            INSTANTIATE_AUTOCOUNTER(autocounter_endpoints.push_back, 0)
#ifdef AUTOCOUNTERBRIDGEMODULE_1_PRESENT
            INSTANTIATE_AUTOCOUNTER(autocounter_endpoints.push_back, 1)
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
class AutoCounterModule_t: public autocounter_module_t
{
public:
    AutoCounterModule_t(int argc, char** argv): autocounter_module_t(argc, argv) {};
    virtual void run() {
        for (auto &autocounter_endpoint: autocounter_endpoints) {
            autocounter_endpoint->init();
        }
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

#ifdef DESIGNNAME_AutoCounter32bRollover
class AutoCounter32bRollover_t: public autocounter_module_t
{
public:
    AutoCounter32bRollover_t(int argc, char** argv): autocounter_module_t(argc, argv) {};
    virtual void run() {
        for (auto &autocounter_endpoint: autocounter_endpoints) {
            autocounter_endpoint->init();
        }
        poke(reset, 1);
        poke(io_a, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(io_a, 1);
        run_and_collect(3000);
    };
};
#endif //DESIGNNAME_AutoCounter32bRollover

#ifdef DESIGNNAME_AutoCounterGlobalResetCondition
class AutoCounterGlobalResetCondition_t: public autocounter_module_t
{
public:
    AutoCounterGlobalResetCondition_t(int argc, char** argv): autocounter_module_t(argc, argv) {};
    virtual void run() {
        for (auto &autocounter_endpoint: autocounter_endpoints) {
            autocounter_endpoint->init();
        }
        poke(reset, 1);
        step(4);
        poke(reset, 0);
        run_and_collect(3000);
    };
};
#endif //DESIGNNAME_AutoCounterModule
