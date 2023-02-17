#include "MultiRegfileTest.h"

class TestMultiSRAM final : public MultiRegfileTest {
public:
  TestMultiSRAM(widget_registry_t &registry,
                const std::vector<std::string> &args,
                std::string_view target_name)
      : MultiRegfileTest(registry, args, target_name) {
    write_first = false;
  }
};

TEST_MAIN(TestMultiSRAM)
