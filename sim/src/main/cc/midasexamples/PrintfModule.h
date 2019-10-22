//See LICENSE for license details.

#include <memory>

#include "simif.h"
#include "bridges/synthesized_prints.h"

class print_module_t: virtual simif_t
{
    public:
        std::unique_ptr<synthesized_prints_t> print_endpoint;
        print_module_t(int argc, char** argv) {
            PRINTBRIDGEMODULE_0_substruct_create;
            std::vector<std::string> args(argv + 1, argv + argc);
            print_endpoint = std::unique_ptr<synthesized_prints_t>(new synthesized_prints_t(this,
                args,
                PRINTBRIDGEMODULE_0_substruct,
                PRINTBRIDGEMODULE_0_print_count,
                PRINTBRIDGEMODULE_0_token_bytes,
                PRINTBRIDGEMODULE_0_idle_cycles_mask,
                PRINTBRIDGEMODULE_0_print_offsets,
                PRINTBRIDGEMODULE_0_format_strings,
                PRINTBRIDGEMODULE_0_argument_counts,
                PRINTBRIDGEMODULE_0_argument_widths,
                PRINTBRIDGEMODULE_0_DMA_ADDR));
        };
        void run_and_collect_prints(int cycles) {
            step(cycles, false);
            while (!done()) {
                print_endpoint->tick();
            }
            print_endpoint->finish();
        };
};

#ifdef DESIGNNAME_PrintfModule
class PrintfModule_t: public print_module_t, virtual simif_t
{
public:
    PrintfModule_t(int argc, char** argv): print_module_t(argc, argv) {};
    virtual void run() {
        print_endpoint->init();
        poke(reset, 1);
        poke(io_a, 0);
        poke(io_b, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(io_a, 1);
        poke(io_b, 1);
        run_and_collect_prints(256);
    };
};
#endif //DESIGNNAME_PrintfModule
