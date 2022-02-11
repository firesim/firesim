// See LICENSE for license details.

#ifndef __SIMIF_PEEK_POKE_H
#define __SIMIF_PEEK_POKE_H

#include "simif.h"
#include <gmp.h>

// This derivation of simif_t is used to implement small integration
// tests where the test writer wants fine grained control over individual
// token channels. In these tests, the peek poke bridge drives all IO of the
// DUT, and there are generally* no other bridges. The driver can then be
// written using a ChiselTesters-like interface consisting of peek, poke and
// expect (defined in this class).
//
// * If other bridges are used their construction, initialization, and tick() invocations
//   must be managed manually. Care must be taken to avoid deadlock, since calls to step.
//   must be blocking for peeks and pokes to capture and drive values at the correct cycles.
//
// Note: This presents the same set of methods legacy simif_t used prior to FireSim 1.13.

class simif_peek_poke_t: public virtual simif_t
{
  public:
    simif_peek_poke_t();
    virtual ~simif_peek_poke_t() { }

    void step(uint32_t n, bool blocking = true);
    // Returns an upper bound for the cycle reached by the target
    // If using blocking steps, this will be ~equivalent to actual_tcycle()
    uint64_t cycles(){ return t; };

    void poke(size_t id, data_t value, bool blocking = true);
    void poke(size_t id, mpz_t& value);
    void target_reset(int pulse_length = 5);

    data_t peek(size_t id, bool blocking = true);
    void peek(size_t id, mpz_t& value);
    data_t sample_value(size_t id);

    bool expect(size_t id, data_t expected);
    bool expect(size_t id, mpz_t& expected);
    bool expect(bool pass, const char *s);

    int teardown();


  private:
    // simulation information
    bool log = true;
    bool pass = true;
    uint64_t t = 0;
    uint64_t fail_t = 0;

    PEEKPOKEBRIDGEMODULE_struct * defaultiowidget_mmio_addrs;

    bool wait_on(size_t flag_addr, double timeout) {
      midas_time_t start = timestamp();
      while (!read(flag_addr))
        if (diff_secs(timestamp(), start) > timeout)
          return false;
      return true;
    }

    bool wait_on_ready(double timeout) {
      return wait_on(this->defaultiowidget_mmio_addrs->READY, timeout);
    }

    bool wait_on_stable_peeks(double timeout) {
      return wait_on(this->defaultiowidget_mmio_addrs->PRECISE_PEEKABLE, timeout);
    }

    std::string blocking_fail = "The test environment has starved the simulator, preventing forward progress.";
};

#endif // __SIMIF_PEEK_POKE_H
