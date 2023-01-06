// See LICENSE for license details.

#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>

#include <memory>
#include <queue>
#include <random>
#include <sstream>

#include <map>

#include <sys/time.h>

#include "bridges/clock.h"
#include "bridges/loadmem.h"
#include "bridges/master.h"

#define TIME_DIV_CONST 1000000.0;
typedef uint64_t midas_time_t;

midas_time_t timestamp();

double diff_secs(midas_time_t end, midas_time_t start);

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
  simulation_t(simif_t &sim, const std::vector<std::string> &args)
      : args(args), sim(sim) {}

  virtual ~simulation_t() {}

  /**
   * Simulation main loop.
   *
   * Simulation drivers implement this method to interface with the target
   * through the simulation interface, providing stimulus and managing bridges.
   * Tests can either use the peek-poke bridge to control the simulation while
   * blocking or rely on the tick method of bridges to advance them as long as
   * there is work to be done.
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

  /**
   * Return true if the simulation timed out.
   *
   * This is solely a driver-side check after target execution finished.
   * It does not stop or verify the target.  The check is used to report if the
   * target exceeded a given number of execution cycles.
   */
  virtual bool simulation_timed_out() { return false; }

  /**
   * Simulation flow.
   *
   * Initialises the simulation, runs it and cleans it up.  Prints profiling
   * info afterwards.  This method is used by simif to transfer control to the
   * simulation after the target is properly initialised.
   *
   * Returns an exit code from the simulation.
   */
  int execute_simulation_flow();

protected:
  /**
   * Saved command-line arguments.
   */
  const std::vector<std::string> args;
  /**
   * Simulation interface.
   */
  simif_t &sim;

private:
  // Simulation performance counters.
  void record_start_times();
  void record_end_times();
  void print_simulation_performance_summary();

  midas_time_t start_time, end_time;
  uint64_t start_hcycle = -1;
  uint64_t end_hcycle = 0;
  uint64_t end_tcycle = 0;
};

;

/** \class simif_t
 *
 *  @brief FireSim's main simulation class.
 *
 *  Historically this god class wrapped all of the features presented by FireSim
 *  / MIDAS-derived simulators. Critically, it declares an interface for
 *  interacting with the host-FPGA, which consist of methods for implementing
 *  32b MMIO (read, write), and latency-insensitive bridge streams (push, pull).
 *  Concrete subclasses of simif_t must be written for metasimulation and each
 *  supported host plaform. See simif_f1_t for an example.
 *  simif_t also provides a few core functions that are tied to bridges and
 *  widgets that must be present in all simulators:
 *
 *  - To track simulation time, it provides methods to interact with the
 *    ClockBridge. This bridge is solely responsible for defining a schedule of
 *    clock edges to simulate, and must be instantiated in all targets. See
 *    actual_tcycle() and hcycle().  Utilities to report performance are based
 *    off these measures of time.
 *
 *  - To read and write into FPGA DRAM, the LoadMem widget provides a
 *    low-bandwidth side channel via MMIO. See read_mem, write_mem,
 *    zero_out_dram.
 */
class simif_t {
public:
  simif_t(const TargetConfig &config, const std::vector<std::string> &args);
  virtual ~simif_t() {}

public:
  /**
   * Returns true if the simulation is complete.
   */
  inline bool done() { return master.is_done(); }

  /**
   * Advance the simulation a given number of steps.
   */
  inline void take_steps(size_t n, bool blocking) {
    return master.step(n, blocking);
  }

  // Host-platform interface. See simif_f1; simif_emul for implementation
  // examples

  /** Bridge / Widget MMIO methods */

  /**
   * @brief Provides a hook to do mmio-related initialization _before_ bridges.
   *
   * This permits setting up core simulation widgets (like stream engines) in a
   * fashion that may vary across different specializations of simif_t.
   */
  virtual void host_mmio_init() = 0;

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
  /**
   * @brief Hint that a stream should bypass any underlying batching
   * optimizations.
   *
   * A user-directed hint that a stream should bypass any underlying batching
   * optimizations. This may permit a future pull to read data that may
   * otherwise remain queued in parts of the host.
   *
   * @param stream_no The index of the stream to flush
   */
  virtual void pull_flush(unsigned int stream_no) = 0;
  /**
   * @brief Analagous to pull_flush but for CPU-to-FPGA streams
   *
   * @param stream_no The index of the stream to flush
   */
  virtual void push_flush(unsigned int stream_no) = 0;

  // End host-platform interface.

  /**
   * Provides the current target cycle of the fastest clock.
   *
   * The target cycle is based on the number of clock tokens enqueued
   * (will report a larger number).
   */
  uint64_t actual_tcycle() { return clock.tcycle(); }

  /**
   * Provides the current host cycle.
   */
  uint64_t actual_hcycle() { return clock.hcycle(); }

  /**
   * Return a reference to the LoadMem widget.
   */
  loadmem_t &get_loadmem() { return loadmem; }

  /// Return the name of the simulated target.
  std::string_view get_target_name() const { return config.target_name; }

private:
  /**
   * Waits for the target to be initialised.
   */
  void target_init();

  void load_mem(std::string filename);

protected:
  /**
   * Simulation main loop.
   */
  int simulation_run();

protected:
  /**
   * Target configuration.
   */
  const TargetConfig config;

  /**
   * LoadMem widget driver.
   */
  loadmem_t loadmem;

  /**
   * ClockBridge driver.
   */
  clockmodule_t clock;

  /**
   * SimulationMaster widged.
   */
  master_t master;

  /**
   * Reference to the user-defined bits of the simulation.
   */
  std::unique_ptr<simulation_t> sim;

  /**
   * Path to load DRAM contents from.
   */
  std::string load_mem_path;

  bool fastloadmem = false;
  // If set, will write all zeros to fpga dram before commencing simulation
  bool do_zero_out_dram = false;

private:
  midas_time_t start_time, end_time;
  uint64_t start_hcycle = -1;
  uint64_t end_hcycle = 0;
  uint64_t end_tcycle = 0;
};

#endif // __SIMIF_H
