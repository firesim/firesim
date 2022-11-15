// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <gmp.h>
#include <map>
#include <queue>
#include <random>
#include <sstream>
#include <sys/time.h>
#define TIME_DIV_CONST 1000000.0;
typedef uint64_t midas_time_t;

midas_time_t timestamp();

double diff_secs(midas_time_t end, midas_time_t start);

typedef std::map<std::string, size_t> idmap_t;
typedef std::map<std::string, size_t>::const_iterator idmap_it_t;

/** \class simif_t
 *
 *  @brief FireSim's main simulation class.
 *
 *  Historically this god class wrapped all of the features presented by FireSim
 *  / MIDAS-derived simulators. Critically, it declares an interface for
 interacting with
 *  the host-FPGA, which consist of methods for implementing 32b MMIO (read,
 *  write), and latency-insensitive bridge streams (push, pull). Concrete
 *  subclasses of simif_t must be written for metasimulation and each supported
 *  host plaform. See simif_f1_t for an example.

 *  simif_t also provides a few core functions that are tied to bridges and
 widgets that
 *  must be present in all simulators:
 *
 *  - To track simulation time, it provides methods to interact with the
 *    ClockBridge. This bridge is solely responsible for defining a schedule of
 *    clock edges to simulate, and must be instantiated in all targets. See
 actual_tcycle() and hcycle().
 *    Utilities to report performance are based off these measures of time.
 *
 *  - To read and write into FPGA DRAM, the LoadMem widget provides a
 *    low-bandwidth side channel via MMIO. See read_mem, write_mem,
 zero_out_dram.
 */
class simif_t {
public:
  simif_t();
  virtual ~simif_t() {}

private:
  // random numbers
  uint64_t seed;
  std::mt19937_64 gen;
  SIMULATIONMASTER_struct *master_mmio_addrs;
  LOADMEMWIDGET_struct *loadmem_mmio_addrs;
  CLOCKBRIDGEMODULE_struct *clock_bridge_mmio_addrs;
  midas_time_t start_time, end_time;
  uint64_t start_hcycle = -1;
  uint64_t end_hcycle = 0;
  uint64_t end_tcycle = 0;

  virtual void load_mem(std::string filename);

public:
  // Simulation APIs
  virtual void init(int argc, char **argv);
  inline bool done() { return read(this->master_mmio_addrs->DONE); }
  inline void take_steps(size_t n, bool blocking) {
    write(this->master_mmio_addrs->STEP, n);
    if (blocking)
      while (!done())
        ;
  }

  // Host-platform interface. See simif_f1; simif_emul for implementation
  // examples

  /**
   * Performs platform-level initialization that for some reason or another
   * cannot be done in the constructor. (For one, currently command line args
   * are not passed to constructor).
   */
  virtual void host_init(int argc, char **argv) = 0;

  /**
   *  Does final platform-specific cleanup before destructors are called.
   */
  virtual int host_finish() = 0;

  /** Bridge / Widget MMIO methods */

  /**
   * @brief 32b MMIO write, issued over the simulation control bus (AXI4-lite).
   *
   * @param addr The address to preform the 32b read in the MMIO address space..
   */
  virtual void write(size_t addr, uint32_t data) = 0;

  /**
   * @brief 32b MMIO read, issued over the simulation control bus (AXI4-lite).
   *
   * @param addr The address to preform the 32b read in the MMIO address space..
   * @returns A uint32_t capturing the read value.
   */
  virtual uint32_t read(size_t addr) = 0;

  /** Bridge Stream Methods */

  /**
   * @brief Dequeues num_bytes of data from an FPGA-to-CPU stream
   *
   * Attempts to copy @num_bytes of data from the head of a bridge stream
   * specified by @stream_idx into a destination buffer (@dest) in the
   * process’s memory space. Non-blocking.
   *
   * @param stream_idx Stream index. Assigned at Golden Gate compile time
   * @param dest Destination buffer into which to copy stream data. (Virtual
   * address.)
   * @param num_bytes Number of bytes to copy.
   * @param required_bytes If pull would return less than this many bytes, it
   * returns 0 instead.
   *
   * @returns Number of bytes copied. Can be less than requested.
   *
   */
  virtual size_t pull(unsigned int stream_idx,
                      void *dest,
                      size_t num_bytes,
                      size_t required_bytes) = 0;

  /**
   * @brief Enqueues num_bytes of data into a CPU-to-FPGA stream
   *
   * Attempts to copy @num_bytes of data from a source buffer (@src) to the
   * tail of the CPU-to-FPGA bridge stream specified by @stream_idx.
   *
   * @param stream_idx Stream index. Assigned at Golden Gate compile time
   * @param src Source buffer from which to copy stream data.
   * @param num_bytes Number of bytes to copy.
   * @param required_bytes If push would accept less than this many bytes, it
   * accepts 0 instead.
   *
   * @returns Number of bytes copied. Can be less than requested.
   *
   */
  virtual size_t push(unsigned int stream_idx,
                      void *src,
                      size_t num_bytes,
                      size_t required_bytes) = 0;

  // End host-platform interface.

  // LOADMEM functions
  void read_mem(size_t addr, mpz_t &value);
  void write_mem(size_t addr, mpz_t &value);
  void write_mem_chunk(size_t addr, mpz_t &value, size_t bytes);
  void zero_out_dram();

  uint64_t get_seed() { return seed; };

  // Returns the current target cycle of the fastest clock in the simulated
  // system, based on the number of clock tokens enqueued (will report a larger
  // number)
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
