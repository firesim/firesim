//See LICENSE for license details.

#include "simif.h"
#include "bridges/synthesized_assertions.h"

class TriggerWiringModule_t: virtual simif_t
{
public:
  synthesized_assertions_t * assert_endpoint;
  TriggerWiringModule_t(int argc, char** argv) {
    ASSERTBRIDGEMODULE_0_substruct_create;
    assert_endpoint = new synthesized_assertions_t(this,
      ASSERTBRIDGEMODULE_0_substruct,
      ASSERTBRIDGEMODULE_0_assert_count,
      ASSERTBRIDGEMODULE_0_assert_messages);
  };
  void run() {
    int assertions_thrown = 0;
    poke(reset, 1);
    step(1);
    poke(reset, 0);
    step(10000, false);
    while (!done()) {
      assert_endpoint->tick();
      if (assert_endpoint->terminate()) {
        assert_endpoint->resume();
        assertions_thrown++;
      }
    }
    expect(assertions_thrown == 0, "No assertions should be thrown");
  }
};
