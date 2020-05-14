// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <sstream>
#include <map>
#include <queue>
#include <random>
#include <gmp.h>
#include <sys/time.h>
#define TIME_DIV_CONST 1000000.0;
typedef uint64_t midas_time_t;

midas_time_t timestamp();

double diff_secs(midas_time_t end, midas_time_t start);

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;

class simif_t
{
  public:
    simif_t();
    virtual ~simif_t() { }
  private:
    // simulation information
    bool log;
    bool pass;
    uint64_t t;
    uint64_t fail_t;
    // random numbers
    uint64_t seed;
    std::mt19937_64 gen;
    SIMULATIONMASTER_struct * master_mmio_addrs;
    LOADMEMWIDGET_struct * loadmem_mmio_addrs;
    PEEKPOKEBRIDGEMODULE_struct * defaultiowidget_mmio_addrs;
    CLOCKBRIDGEMODULE_struct * clock_bridge_mmio_addrs;
    midas_time_t sim_start_time;

    inline void take_steps(size_t n, bool blocking) {
      write(this->master_mmio_addrs->STEP, n);
      if (blocking) while(!done());
    }
    virtual void load_mem(std::string filename);

  public:
    // Simulation APIs
    virtual void init(int argc, char** argv, bool log = false);
    virtual int finish();
    virtual void step(uint32_t n, bool blocking = true);
    inline bool done() { return read(this->master_mmio_addrs->DONE); }

    // Widget communication
    virtual void write(size_t addr, data_t data) = 0;
    virtual data_t read(size_t addr) = 0;
    virtual ssize_t pull(size_t addr, char *data, size_t size) = 0;
    virtual ssize_t push(size_t addr, char *data, size_t size) = 0;

    inline void poke(size_t id, data_t value) {
      if (log) fprintf(stderr, "* POKE %s.%s <- 0x%x *\n",
        TARGET_NAME, INPUT_NAMES[id], value);
      write(INPUT_ADDRS[id], value);
    }

    inline data_t peek(size_t id) {
      data_t value = read(((unsigned int*)OUTPUT_ADDRS)[id]);
      if (log) fprintf(stderr, "* PEEK %s.%s -> 0x%x *\n",
        TARGET_NAME, (const char*)OUTPUT_NAMES[id], value);
      return value;
    }

    inline bool expect(size_t id, data_t expected) {
      data_t value = peek(id);
      bool pass = value == expected;
      if (log) fprintf(stderr, "* EXPECT %s.%s -> 0x%x ?= 0x%x : %s\n",
        TARGET_NAME, (const char*)OUTPUT_NAMES[id], value, expected, pass ? "PASS" : "FAIL");
      return expect(pass, NULL);
    }

    inline bool expect(bool pass, const char *s) {
      if (log && s) fprintf(stderr, "* %s : %s *\n", s, pass ? "PASS" : "FAIL");
      if (this->pass && !pass) fail_t = t;
      this->pass &= pass;
      return pass;
    }

    void poke(size_t id, mpz_t& value);
    void peek(size_t id, mpz_t& value);
    bool expect(size_t id, mpz_t& expected);

    // LOADMEM functions
    void read_mem(size_t addr, mpz_t& value);
    void write_mem(size_t addr, mpz_t& value);
    void write_mem_chunk(size_t addr, mpz_t& value, size_t bytes);
    void zero_out_dram();

    uint64_t get_seed() { return seed; };

    // A default reset scheme that holds reset high for pulse_length cycles
    void target_reset(int pulse_length = 5);

    // Returns an upper bound for the cycle reached by the target
    // If using blocking steps, this will be ~equivalent to actual_tcycle()
    uint64_t cycles(){ return t; };
    // Returns the current target cycle of the fastest clock in the simulated system, based
    // on the number of clock tokens enqueued (will report a larger number)
    uint64_t actual_tcycle();
    // Returns the current host cycle as measured by a hardware counter
    uint64_t hcycle();
    uint64_t rand_next(uint64_t limit) { return gen() % limit; }

};

#endif // __SIMIF_H
