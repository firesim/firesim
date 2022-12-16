#include "MultiRegfileTest.h"

class TestMultiSRAM final : public MultiRegfileTest {
public:
  TestMultiSRAM(const std::vector<std::string> &args, simif_t *simif)
      : MultiRegfileTest(args, simif) {
    write_first = false;
  }
};

TEST_MAIN(TestMultiSRAM)
