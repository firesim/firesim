// See LICENSE for license details.

#include "AssertTest.h"
#include "TestHarness.h"

class TestAssertModule final : public AssertTest {
public:
  using AssertTest::AssertTest;

  void run_test() override {
    assert(assert_endpoints.size() == 1 && "expected one assert");
    auto *assert_endpoint = assert_endpoints[0];

    int assertions_thrown = 0;
    poke("reset", 1);
    poke("io_a", 0);
    poke("io_b", 0);
    poke("io_c", 0);
    step(1);
    poke("reset", 0);
    int num_test_cases = 3;

    for (int test_case = 0; test_case < num_test_cases; test_case++) {
      switch (test_case) {
      case 0:
        poke("io_cycleToFail", 1024);
        poke("io_a", 1);
        step(2048, false);
        break;
      case 1:
        poke("io_cycleToFail", 3056);
        poke("io_b", 0);
        poke("io_c", 1);
        step(2048, false);
        break;
      case 2:
        poke("io_c", 0);
        poke("io_cycleToFail", 5183);
        step(2048, false);
        break;
      default:
        throw std::runtime_error("No test case associated with id " +
                                 std::to_string(test_case));
        break;
      }

      auto &assert_endpoint = *assert_endpoints[0];
      while (!peek_poke.is_done()) {
        assert_endpoint.tick();
        if (assert_endpoint.terminate()) {
          assert_endpoint.resume();
          assertions_thrown++;
        }
      }
    }
    expect(assertions_thrown == 3, "EXPECT: Three assertions thrown");
  };
};

TEST_MAIN(TestAssertModule)
