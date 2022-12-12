// See LICENSE for license details.

#include "ShiftRegisterTest.h"

// This is basically just the shift register but with additional XDC
// constraints added.
class TestCustomConstraints final : public ShiftRegisterTest {
public:
  using ShiftRegisterTest::ShiftRegisterTest;
};

TEST_MAIN(TestCustomConstraints)
