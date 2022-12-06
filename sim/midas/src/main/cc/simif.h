// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>

#include <memory>
#include <queue>
#include <random>
#include <sstream>

#include <gmp.h>
#include <map>
#include <sys/time.h>

#define TIME_DIV_CONST 1000000.0;
typedef uint64_t midas_time_t;

midas_time_t timestamp();

double diff_secs(midas_time_t end, midas_time_t start);

typedef std::map<std::string, size_t> idmap_t;
typedef std::map<std::string, size_t>::const_iterator idmap_it_t;

/**
 * Interface for a simulation implementation.
 *
 * `simulation_t` interacts with the simulation, initialising it, running and
 * and running finalisation logic at the end.  All simulation implementations
 * (midasexamples, firesim, bridges, fasedtests) derive this interface,
 * implementing the driver-specific logic.
 */
class simulation_t {
public:
  simulation_t(const std::vector<std::string> &args) : args(args) {}

  virtual ~simulation_t() {}

  /**
   * Simulation main loop.
   *
   * @return Exit code to return to the system.
   */
  virtual int simulation_run() = 0;

  /**
   * Simulation initialization.
   *
   * Presently, this is used to both construct bridge instances and call their
   * `init` methods. MMIO is allowed at this stage.
   *
   * The software-only and the software-hardware phases of initialisation should
   * be further split in the future.
   */
  virtual void simulation_init() {}

  /**
   * Simulation finalization.
   *
   * Should call the `finish` method on all registered bridges.
   */
  virtual void simulation_finish() {}

protected:
  const std::vector<std::string> args;
};

typedef struct SIMULATIONMASTER_struct {
  uint64_t STEP;
  uint64_t DONE;
  uint64_t INIT_DONE;
} SIMULATIONMASTER_struct;

typedef struct LOADMEMWIDGET_struct {
  uint64_t W_ADDRESS_H;
  uint64_t W_ADDRESS_L;
  uint64_t W_LENGTH;
  uint64_t ZERO_OUT_DRAM;
  uint64_t W_DATA;
  uint64_t ZERO_FINISHED;
  uint64_t R_ADDRESS_H;
  uint64_t R_ADDRESS_L;
  uint64_t R_DATA;
} LOADMEMWIDGET_struct;

typedef struct CLOCKBRIDGEMODULE_struct {
  uint64_t hCycle_0;
  uint64_t hCycle_1;
  uint64_t hCycle_latch;
  uint64_t tCycle_0;
  uint64_t tCycle_1;
  uint64_t tCycle_latch;
} CLOCKBRIDGEMODULE_struct;

SIMULATIONMASTER_checks;
LOADMEMWIDGET_checks;
CLOCKBRIDGEMODULE_checks;

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
  simif_t(const std::vector<std::string> &args);
  virtual ~simif_t() {}

public:
  /// Returns true if the simulation is complete.
  inline bool done() { return read(master_mmio_addrs.DONE); }

  /// Advances the simulation for a given number of steps.
  inline void take_steps(size_t n, bool blocking) {
    write(master_mmio_addrs.STEP, n);
    if (blocking)
      while (!done())
        ;
  }

  /// Entry point to the simulation.
  virtual int run() = 0;

  // Host-platform interface. See simif_f1; simif_emul for implementation
  // examples

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

  /**
   * Returns the seed of the random-number generator.
   */
  uint64_t get_seed() const { return seed; };

  /**
   * Returns the next available random number, modulo limit.
   */
  uint64_t rand_next(uint64_t limit) { return gen() % limit; }

  /**
   * Provides the current target cycle of the fastest clock.
   *
   * The target cycle is based on the number of clock tokens enqueued
   * (will report a larger number).
   */
  uint64_t actual_tcycle();

  /**
   * Returns the current host cycle as measured by a hardware counter
   */
  uint64_t hcycle();

  /**
   * Returns the last completed cycle number.
   */
  uint64_t get_end_tcycle() { return end_tcycle; }

  // LOADMEM functions.
  void read_mem(size_t addr, mpz_t &value);
  void write_mem(size_t addr, mpz_t &value);
  void write_mem_chunk(size_t addr, mpz_t &value, size_t bytes);

private:
  /**
   * Waits for the target to be initialised.
   */
  void target_init();

  // Simulation performance counters.
  void record_start_times();
  void record_end_times();
  void print_simulation_performance_summary();

  // Helper to zero out all DRAM.
  void zero_out_dram();

  void load_mem(std::string filename);

protected:
  /**
   * Simulation main loop.
   */
  int simulation_run();

protected:
  /**
   * Reference to the user-defined bits of the simulation.
   */
  std::unique_ptr<simulation_t> sim;

  std::string loadmem;
  bool fastloadmem = false;
  // If set, will write all zeros to fpga dram before commencing simulation
  bool do_zero_out_dram = false;

private:
  // random numbers
  uint64_t seed = 0;
  std::mt19937_64 gen;
  const SIMULATIONMASTER_struct master_mmio_addrs;
  const LOADMEMWIDGET_struct loadmem_mmio_addrs;
  const CLOCKBRIDGEMODULE_struct clock_bridge_mmio_addrs;
  midas_time_t start_time, end_time;
  uint64_t start_hcycle = -1;
  uint64_t end_hcycle = 0;
  uint64_t end_tcycle = 0;
};

#endif // __SIMIF_H
