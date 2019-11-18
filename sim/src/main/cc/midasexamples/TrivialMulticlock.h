//See LICENSE for license details.

#include "simif.h"

class MultiClockChecker {
  public:
   simif_t * sim;
   uint32_t field_address;
   int numerator, denominator;
   int cycle = 0;
   uint32_t expected_value;

   MultiClockChecker(simif_t * sim, uint32_t field_address, int numerator, int denominator):
     sim(sim), field_address(field_address), numerator(numerator), denominator(denominator) {};
   void expect_and_update(uint64_t poked_value){
      if (cycle == 0) {
          expected_value = poked_value;
      } else {
        sim->expect(field_address, expected_value);
        if ((cycle * numerator) / denominator > ((cycle - 1) * numerator)/ denominator) {
          expected_value = poked_value;
        }
      }
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
    checkers.push_back(new MultiClockChecker(this, threeSeventhsOut, 3, 7));

    uint32_t current = rand_next(limit);
    poke(in, current);
    current = rand_next(limit);
    step(1);
    for(int i = 1; i < 1024; i++){
      for(auto checker: checkers) checker->expect_and_update(current);
      poke(in, current);
      current = rand_next(limit);
      step(1);
    }
  }
};
