//See LICENSE for license details.

#include <memory>

#include "simif.h"
#include "endpoints/synthesized_prints.h"

class print_module_t: virtual simif_t
{
    public:
        std::unique_ptr<synthesized_prints_t> print_endpoint;
        print_module_t(int argc, char** argv) {
            SYNTHESIZEDPRINT_substruct_create;
            std::vector<std::string> args(argv + 1, argv + argc);
            print_endpoint = std::unique_ptr<synthesized_prints_t>(new synthesized_prints_t(this,
                args,
                SYNTHESIZEDPRINT_substruct,
                SYNTHESIZEDPRINT_print_count,
                SYNTHESIZEDPRINT_token_bytes,
                SYNTHESIZEDPRINT_idle_cycles_mask,
                SYNTHESIZEDPRINT_print_offsets,
                SYNTHESIZEDPRINT_format_strings,
                SYNTHESIZEDPRINT_argument_counts,
                SYNTHESIZEDPRINT_argument_widths,
                SYNTHESIZEDPRINT_DMA_ADDR));
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
