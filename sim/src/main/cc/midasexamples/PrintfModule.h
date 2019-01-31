//See LICENSE for license details.

#include <memory>

#include "simif.h"
#include "endpoints/synthesized_prints.h"

class print_module_t: virtual simif_t
{
    public:
        std::unique_ptr<synthesized_prints_t> print_endpoint;
        print_module_t(int argc, char** argv) {
            PRINTWIDGET_0_substruct_create;
            std::vector<std::string> args(argv + 1, argv + argc);
            print_endpoint = std::unique_ptr<synthesized_prints_t>(new synthesized_prints_t(this,
                args,
                PRINTWIDGET_0_substruct,
                PRINTWIDGET_0_print_count,
                PRINTWIDGET_0_token_bytes,
                PRINTWIDGET_0_idle_cycles_mask,
                PRINTWIDGET_0_print_offsets,
                PRINTWIDGET_0_format_strings,
                PRINTWIDGET_0_argument_counts,
                PRINTWIDGET_0_argument_widths,
                PRINTWIDGET_0_DMA_ADDR));
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
        poke(a, 0);
        poke(b, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(a, 1);
        poke(b, 1);
        run_and_collect_prints(256);
    };
};
#endif //DESIGNNAME_PrintfModule
