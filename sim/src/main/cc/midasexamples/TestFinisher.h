//See LICENSE for license details.

#include "simif_peek_poke.h"
#include "bridges/test_finisher.h"

class TestFinisher_t: public simif_peek_poke_t
{
public:
    test_finisher_t * finisher;
    TestFinisher_t(int argc, char** argv) {
        TESTFINISHERBRIDGEMODULE_0_substruct_create;
        finisher = new test_finisher_t(
          this, 
          TESTFINISHERBRIDGEMODULE_0_substruct);
    };
    void run() {
        poke(reset, 1);
				int inCycle = 10;
				int outCycle = inCycle+2;
        poke(io_doneInCycle, inCycle);
        poke(io_doneTestVec, 1);
        poke(io_doneErrCode, 0);
        step(1);
        poke(reset, 0);
        while (!finisher->terminate()) {
						step(1);
						finisher->tick();
        }
        expect(io_doneOutCycle, outCycle);
    };
};
