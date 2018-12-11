//See LICENSE for license details.

#include "simif.h"
#include "endpoints/synthesized_prints.h"

class PrintfModule_t: virtual simif_t
{
public:
    synthesized_prints_t * print_endpoint;
    PrintfModule_t(int argc, char** argv) {
        PRINTWIDGET_0_substruct_create;
        std::vector<std::string> args(argv + 1, argv + argc);
        print_endpoint = new synthesized_prints_t(this,
                                                  args,
                                                  PRINTWIDGET_0_substruct,
                                                  PRINTWIDGET_0_print_count,
                                                  PRINTWIDGET_0_print_offsets,
                                                  PRINTWIDGET_0_format_strings,
                                                  PRINTWIDGET_0_argument_counts,
                                                  PRINTWIDGET_0_argument_widths,
                                                  PRINTWIDGET_0_DMA_ADDR);
    };
    void run() {
        poke(reset, 1);
        poke(a, 0);
        poke(b, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(a, 1);
        poke(b, 1);
        step(128, false);
        while (!done()) {
            print_endpoint->tick();
        }
        expect(false, "Flesh me out");
    };

    ~PrintfModule_t() {
      delete print_endpoint;
    };
};
