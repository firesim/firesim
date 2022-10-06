// See LICENSE for license details.

#include "ShiftRegister.h"

// This is basically just the shift register but with additional XDC
// constraints added.
class CustomConstraints_t : public ShiftRegister_t {
public:
  using ShiftRegister_t::ShiftRegister_t;
};
