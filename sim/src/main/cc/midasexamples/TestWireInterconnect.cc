// See LICENSE for license details.

#include "TestHarness.h"

class TestWireInterconnect final : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() {
    gmp_randstate_t rstate;
    gmp_randinit_default(rstate);
    gmp_randseed_ui(rstate, simif->get_seed());
    mpz_t vbIn_bar_bits, vbOut_bar_bits;
    mpz_inits(vbIn_bar_bits, NULL);
    int width = 128;

    target_reset();
    for (int i = 0; i < 64; i++) {

      // Poke channel A
      uint32_t in = random() % 16;
      poke("io_aIn", in);

      uint32_t vbIn_foo = random() % 16;
      uint32_t vbIn_bar_valid = random() % 2;
      mpz_urandomb(vbIn_bar_bits, rstate, width);

      // These pokes also serve to provide some host delay for the poke above to
      // propagate through the simulator
      poke("io_bIn_foo", vbIn_foo);
      poke("io_bIn_bar_valid", vbIn_bar_valid);
      poke("io_bIn_bar_bits", vbIn_bar_bits);

      // Expect wire outputs -- this propagate combinationally before calling
      // step
      expect("io_aOut", in);
      step(1);

      // Expect registered outputs
      expect("io_bOut_foo", vbIn_foo);
      expect("io_bOut_bar_valid", vbIn_bar_valid);
      expect("io_bOut_bar_bits", vbIn_bar_bits);
    }
  }
};

TEST_MAIN(TestWireInterconnect)
