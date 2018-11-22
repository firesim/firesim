//See LICENSE for license details.

#include "simif.h"

class WireInterconnect_t: public virtual simif_t
{
public:
  WireInterconnect_t(int argc, char** argv) {}
  void run() {
    gmp_randstate_t rstate;
    gmp_randinit_default(rstate);
    gmp_randseed_ui(rstate, get_seed());
    mpz_t vbIn_bar_bits, vbOut_bar_bits;
    mpz_inits(vbIn_bar_bits, NULL);
    int width = 128;

    target_reset();
    for (int i = 0 ; i < 64 ; i++) {

      // Poke channel A
      uint32_t in     = rand_next(16);
      poke(aIn,    in);

      uint32_t vbIn_foo        = rand_next(16);
      uint32_t vbIn_bar_valid  = rand_next(2);
      mpz_urandomb(vbIn_bar_bits, rstate, width);

      // These peeks also serve to provide some host delay for the poke above to
      // propagate through the simulator
      poke(bIn_foo, vbIn_foo);
      poke(bIn_bar_valid, vbIn_bar_valid);
      poke(bIn_bar_bits, vbIn_bar_bits);

      // Expect wire outputs
      expect(aOut, in);
      step(1);

      // Expect registered outputs
      expect(bOut_foo, vbIn_foo);
      expect(bOut_bar_valid, vbIn_bar_valid);
      expect(bOut_bar_bits, vbIn_bar_bits);
    }
  }
};
