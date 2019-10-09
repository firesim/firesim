// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <sstream>
#include <map>
#include <queue>
#include <random>
#ifdef ENABLE_SNAPSHOT
#include "sample/sample.h"
#endif
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
    // Returns the current target cycle as measured by a hardware counter in the DefaultIOWidget
    // (# of reset tokens generated)
    uint64_t actual_tcycle();
    // Returns the current host cycle as measured by a hardware counter
    uint64_t hcycle();
    uint64_t rand_next(uint64_t limit) { return gen() % limit; }

#ifdef ENABLE_SNAPSHOT
  private:
    // sample information
#ifdef KEEP_SAMPLES_IN_MEM
    sample_t** samples;
#endif
    sample_t* last_sample;
    size_t sample_num;
    size_t last_sample_id;
    std::string sample_file;
    uint64_t sample_cycle;
    uint64_t snap_cycle;

    size_t trace_count;

    // profile information
    bool profile;
    size_t sample_count;
    midas_time_t sample_time;

    void init_sampling(int argc, char** argv);
    void finish_sampling();
    void reservoir_sampling(size_t n);
    void deterministic_sampling(size_t n);
    size_t trace_ready_valid_bits(
      sample_t* sample, bool poke, size_t id, size_t bits_id);
    inline void save_sample();

  protected:
    size_t tracelen;
    sample_t* read_snapshot(bool load = false);
    sample_t* read_traces(sample_t* s);

  public:
    uint64_t get_snap_cycle() const {
      return snap_cycle;
    }
    uint64_t get_sample_cycle() const {
      return sample_cycle;
    }
    void set_sample_cycle(uint64_t cycle) {
      sample_cycle = cycle;
    }
    void set_trace_count(uint64_t count) {
      trace_count = count;
    }
#endif
};

#endif // __SIMIF_H
