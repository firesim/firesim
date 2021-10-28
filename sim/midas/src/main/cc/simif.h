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
    // random numbers
    uint64_t seed;
    std::mt19937_64 gen;
    SIMULATIONMASTER_struct * master_mmio_addrs;
    LOADMEMWIDGET_struct * loadmem_mmio_addrs;
    CLOCKBRIDGEMODULE_struct * clock_bridge_mmio_addrs;
    midas_time_t start_time, end_time;
    uint64_t start_hcycle = -1;
    uint64_t end_hcycle = 0;
    uint64_t end_tcycle = 0;

    virtual void load_mem(std::string filename);

  public:
    // Simulation APIs
    virtual void init(int argc, char** argv, bool log = false);
    virtual int finish() = 0;
    inline bool done() { return read(this->master_mmio_addrs->DONE); }
    inline void take_steps(size_t n, bool blocking) {
      write(this->master_mmio_addrs->STEP, n);
      if (blocking) while(!done());
    }

    // Widget communication
    virtual void write(size_t addr, data_t data) = 0;
    virtual data_t read(size_t addr) = 0;
    virtual ssize_t pull(size_t addr, char *data, size_t size) = 0;
    virtual ssize_t push(size_t addr, char *data, size_t size) = 0;

    // LOADMEM functions
    void read_mem(size_t addr, mpz_t& value);
    void write_mem(size_t addr, mpz_t& value);
    void write_mem_chunk(size_t addr, mpz_t& value, size_t bytes);
    void zero_out_dram();

    uint64_t get_seed() { return seed; };

    // Returns the current target cycle of the fastest clock in the simulated system, based
    // on the number of clock tokens enqueued (will report a larger number)
    uint64_t actual_tcycle();
    // Returns the current host cycle as measured by a hardware counter
    uint64_t hcycle();
    uint64_t rand_next(uint64_t limit) { return gen() % limit; }

    void record_start_times();
    void record_end_times();
    uint64_t get_end_tcycle() { return end_tcycle; }
    void print_simulation_performance_summary();
};

#endif // __SIMIF_H
