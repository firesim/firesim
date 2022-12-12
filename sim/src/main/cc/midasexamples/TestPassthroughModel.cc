// See LICENSE for license details.

#include "PassthroughModelTest.h"

class TestPassthroughModel final : public PassthroughModelDriver {
public:
  using PassthroughModelDriver::PassthroughModelDriver;
};

TEST_MAIN(TestPassthroughModel)
