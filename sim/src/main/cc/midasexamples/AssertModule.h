//See LICENSE for license details.

#include "simif.h"
#include "endpoints/synthesized_assertions.h"

class AssertModule_t: virtual simif_t
{
public:
    synthesized_assertions_t * assert_endpoint;
    AssertModule_t(int argc, char** argv) {
        ASSERTIONWIDGET_0_substruct_create;
        assert_endpoint = new synthesized_assertions_t(this, ASSERTIONWIDGET_0_substruct);
    };
    void run() {
        int assertions_thrown = 0;
        poke(reset, 1);
        poke(io_a, 0);
        poke(io_b, 0);
        poke(io_c, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(io_cycleToFail, 1024);
        poke(io_a, 1);
        step(4096, false);
        while (!done()) {
            assert_endpoint->tick();
            if (assert_endpoint->terminate()) {
                if (assertions_thrown == 0) {
                    poke(io_cycleToFail, 2048);
                    poke(io_b, 1);
                    poke(io_a, 0);
                } else if (assertions_thrown == 1) {
                    poke(io_cycleToFail, 3056);
                    poke(io_b, 0);
                    poke(io_c, 1);
                } else {
                    poke(io_c, 0);
                }
                assert_endpoint->resume();
                assertions_thrown++;
            }
        }
        expect(assertions_thrown == 3, "EXPECT: Three assertions thrown");
    };
};
