//See LICENSE for license details.

#include "simif.h"
#include "endpoints/synthesized_assertions.h"

class AssertModule_t: virtual simif_t
{
public:
    synthesized_assertions_t * assert_endpoint;
    AssertModule_t(int argc, char** argv) { assert_endpoint = new synthesized_assertions_t(this); };
    void run() {
        int assertions_thrown = 0;
        poke(reset, 1);
        poke(io_a, 0);
        poke(io_b, 0);
        step(1);
        poke(reset, 0);
        step(1);
        poke(io_cycleToFail, 1024);
        poke(io_a, 1);
        step(2049, false);
        while (!done()) {
            assert_endpoint->tick();
            if (assert_endpoint->terminate()) {
                if (assertions_thrown == 0) {
                    poke(io_cycleToFail, 2048);
                    poke(io_b, 1);
                    poke(io_a, 0);
                    assert_endpoint->resume();
                } else {
                    poke(io_b, 0);
                    assert_endpoint->resume();
                }
                assertions_thrown++;
            }
        }
        expect(assertions_thrown == 2, "EXPECT: Two assertions thrown");
    };
};
