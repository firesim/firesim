
//See LICENSE for license details.

#include "simif.h"
#include "bridges/synthesized_assertions.h"
#include <vector>

class AssertTorture_t: virtual simif_t
{
public:
    std::vector<synthesized_assertions_t *> assert_endpoints;
    AssertTorture_t(int argc, char** argv) {
#ifdef ASSERTBRIDGEMODULE_0_PRESENT
    ASSERTBRIDGEMODULE_0_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_0_substruct,
                                                   ASSERTBRIDGEMODULE_0_assert_count,
                                                   ASSERTBRIDGEMODULE_0_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_1_PRESENT
    ASSERTBRIDGEMODULE_1_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_1_substruct,
                                                   ASSERTBRIDGEMODULE_1_assert_count,
                                                   ASSERTBRIDGEMODULE_1_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_2_PRESENT
    ASSERTBRIDGEMODULE_2_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_2_substruct,
                                                   ASSERTBRIDGEMODULE_2_assert_count,
                                                   ASSERTBRIDGEMODULE_2_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_3_PRESENT
    ASSERTBRIDGEMODULE_3_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_3_substruct,
                                                   ASSERTBRIDGEMODULE_3_assert_count,
                                                   ASSERTBRIDGEMODULE_3_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_4_PRESENT
    ASSERTBRIDGEMODULE_4_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_4_substruct,
                                                   ASSERTBRIDGEMODULE_4_assert_count,
                                                   ASSERTBRIDGEMODULE_4_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_5_PRESENT
    ASSERTBRIDGEMODULE_5_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_5_substruct,
                                                   ASSERTBRIDGEMODULE_5_assert_count,
                                                   ASSERTBRIDGEMODULE_5_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_6_PRESENT
    ASSERTBRIDGEMODULE_6_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_6_substruct,
                                                   ASSERTBRIDGEMODULE_6_assert_count,
                                                   ASSERTBRIDGEMODULE_6_assert_messages));
#endif
#ifdef ASSERTBRIDGEMODULE_7_PRESENT
    ASSERTBRIDGEMODULE_7_substruct_create
    assert_endpoints.push_back(new synthesized_assertions_t(this,
                                                   ASSERTBRIDGEMODULE_7_substruct,
                                                   ASSERTBRIDGEMODULE_7_assert_count,
                                                   ASSERTBRIDGEMODULE_7_assert_messages));
#endif
    };
    void run() {
        target_reset(2);
        step(40000, false);
        while (!done()) {
            for (auto ep: assert_endpoints) {
                ep->tick();
                if (ep->terminate()) {
                    ep->resume();
                }
            }
        }
    };
};
