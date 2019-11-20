//See LICENSE for license details.

#include "simif.h"

class MultiClockChecker {
  public:
   simif_t * sim;
   uint32_t field_address;
   int numerator, denominator;
   int cycle = -1;
   uint32_t expected_value;
   uint32_t fast_domain_reg, slow_domain_reg, fast_domain_reg_out;

   MultiClockChecker(simif_t * sim, uint32_t field_address, int numerator, int denominator):
     sim(sim), field_address(field_address), numerator(numerator), denominator(denominator) {};
   void expect_and_update(uint64_t poked_value){
      if (cycle > 1 ) sim->expect(field_address, fast_domain_reg_out);
      if (cycle < 1) {
        fast_domain_reg_out = slow_domain_reg;
        slow_domain_reg = fast_domain_reg;
      } else {
        fast_domain_reg_out =  slow_domain_reg;
        if (((cycle * numerator) / denominator) > (((cycle - 1) * numerator)/ denominator)) {
          // TODO: Handle the case where numerator * cycle is not a multiple of the division
          //if (((cycle * numerator) % denominator) != 0) {
          //  fast_domain_reg_out = slow_domain_reg;
          //  slow_domain_reg = poked_value;
          //} else {
          slow_domain_reg = fast_domain_reg;
          //}
        }
      }
      fast_domain_reg = poked_value;
      cycle++;
   };
};

class TrivialMulticlock_t: virtual simif_t
{
public:
  TrivialMulticlock_t(int argc, char** argv) {}
  void run() {
    uint64_t limit = 256;
    std::vector<MultiClockChecker*> checkers;
    checkers.push_back(new MultiClockChecker(this, halfOut, 1, 2));
    checkers.push_back(new MultiClockChecker(this, thirdOut, 1, 3));
    // Resolve bug in PeekPoke Bridge
    //checkers.push_back(new MultiClockChecker(this, threeSeventhsOut, 3, 7));

    uint32_t current = rand_next(limit);
    for(int i = 1; i < 1024; i++){
      for(auto checker: checkers) checker->expect_and_update(i);
      poke(in, i);
      current = rand_next(limit);
      step(1);
    }
  }
};
