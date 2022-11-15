// See LICENSE for license details.

#include "bridges/synthesized_assertions.h"
#include "simif_peek_poke.h"

class AssertModule_t : public simif_peek_poke_t {
public:
  synthesized_assertions_t *assert_endpoint;
  AssertModule_t(int argc, char **argv) {
    ASSERTBRIDGEMODULE_0_substruct_create;
    std::vector<std::string> args(argv + 1, argv + argc);
    assert_endpoint =
        new synthesized_assertions_t(this,
                                     args,
                                     ASSERTBRIDGEMODULE_0_substruct,
                                     ASSERTBRIDGEMODULE_0_assert_messages);
  };
  void run() {
    int assertions_thrown = 0;
    assert_endpoint->init();
    poke(reset, 1);
    poke(io_a, 0);
    poke(io_b, 0);
    poke(io_c, 0);
    step(1);
    poke(reset, 0);
    int num_test_cases = 3;
    int test_case = 0;
    for (int test_case = 0; test_case < num_test_cases; test_case++) {
      switch (test_case) {
      case 0:
        poke(io_cycleToFail, 1024);
        poke(io_a, 1);
        step(2048, false);
        break;
      case 1:
        poke(io_cycleToFail, 3056);
        poke(io_b, 0);
        poke(io_c, 1);
        step(2048, false);
        break;
      case 2:
        poke(io_c, 0);
        poke(io_cycleToFail, 5183);
        step(2048, false);
        break;
      default:
        throw new std::runtime_error("No test case associated with id " +
                                     std::to_string(test_case));
        break;
      }
      while (!done()) {
        assert_endpoint->tick();
        if (assert_endpoint->terminate()) {
          assert_endpoint->resume();
          assertions_thrown++;
        }
      }
    }
    expect(assertions_thrown == 3, "EXPECT: Three assertions thrown");
  };
};
