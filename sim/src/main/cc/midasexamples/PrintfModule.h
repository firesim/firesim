//See LICENSE for license details.

#include "simif.h"

class PrintfModule_t: virtual simif_t
{
public:
    PrintModule_t(int argc, char** argv) { };
    void run() {
        expect(false, "Flesh me out");
    };
};
