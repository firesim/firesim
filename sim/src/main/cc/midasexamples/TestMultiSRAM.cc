#include "MultiRegfileTest.h"

class TestMultiSRAM final : public MultiRegfileTest {
public:
  TestMultiSRAM(const std::vector<std::string> &args) : MultiRegfileTest(args) {
    write_first = false;
  }
};

TEST_MAIN(TestMultiSRAM)
