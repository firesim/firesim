//See LICENSE for license details.

#include "simif.h"
#include "bridges/synthesized_assertions.h"

class MultiClockAssertModule_t: virtual simif_t
{
public:
    std::vector<synthesized_assertions_t *> assert_endpoints;
    synthesized_assertions_t * full_rate_assert_ep;
    synthesized_assertions_t * half_rate_assert_ep;
    MultiClockAssertModule_t(int argc, char** argv) {
        ASSERTBRIDGEMODULE_0_substruct_create;
        ASSERTBRIDGEMODULE_1_substruct_create;
        full_rate_assert_ep = new synthesized_assertions_t(this, ASSERTBRIDGEMODULE_0_substruct);
        half_rate_assert_ep = new synthesized_assertions_t(this, ASSERTBRIDGEMODULE_1_substruct);
        assert_endpoints.push_back(full_rate_assert_ep);
        assert_endpoints.push_back(half_rate_assert_ep);
    };
    void run() {
        int assertions_thrown = 0;
        poke(reset, 0);
        poke(fullrate_pulseLength, 2);
        poke(fullrate_cycle, 186);
        poke(halfrate_pulseLength, 2);
        poke(halfrate_cycle, 129);
        step(256, false);
        while (!done()) {
            for (auto ep: assert_endpoints) {
                ep->tick();
                if (ep->terminate()) {
                    ep->resume();
                    assertions_thrown++;
                }
            }
        }
        expect(assertions_thrown == 3, "EXPECT: Two assertions thrown");
    };
};
