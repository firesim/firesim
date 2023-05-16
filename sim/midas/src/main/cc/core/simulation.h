// See LICENSE for license details.

#ifndef __SIMULATION_H
#define __SIMULATION_H

#include <optional>
#include <string>
#include <vector>

#include "core/timing.h"

class simif_t;
class widget_registry_t;
class clockmodule_t;

/**
 * Interface for a simulation implementation.
 *
 * `simulation_t` interacts with the simulation, initialising it, running and
 * and running finalisation logic at the end.  All simulation implementations
 * (midasexamples, firesim, bridges, fasedtests) derive this interface,
 * implementing the driver-specific logic.
 *
 * - To track simulation time, this class automatically interfaces with the
 *   clock module and incorporates utilities to report performance are based
 *   off these measures of time.
 *
 * - Via the LoadMem widget, this class sets up the initial contents of DRAM.
 */
class simulation_t {
public:
  simulation_t(widget_registry_t &registry,
               const std::vector<std::string> &args);

  virtual ~simulation_t() = default;

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
  virtual void simulation_init();

  /**
   * Simulation finalization.
   *
   * Should call the `finish` method on all registered bridges.
   */
  virtual void simulation_finish();

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

private:
  /**
   * Waits until the hardware is initialised.
   *
   * This method blocks and must be called before any MMIO is performed.
   */
  void wait_for_init();

  /**
   * Initialises the memory system of the simulation.
   */
  void init_dram();

  // Simulation performance counters.
  void record_start_times();
  void record_end_times();
  void print_simulation_performance_summary();

protected:
  /**
   * Widget registry.
   */
  widget_registry_t &registry;

private:
  /**
   * Name of the simulated target.
   */
  std::string target_name;

  /**
   * Reference to the clock widget.
   */
  clockmodule_t &clock;

  /**
   * Path to load DRAM contents from, unless +fastloadmem is set.
   */
  std::string load_mem_path;

  /**
   * If set, will write all zeros to fpga dram before commencing simulation
   */
  bool do_zero_out_dram = false;

  /**
   * If set, read the presence register, check it, and exit the simulation
   * cleanly
   */
  bool check_fingerprint_only = false;

  /**
   * If set, write the presence register and exit the simulation cleanly
   */
  std::optional<uint32_t> write_fingerprint_only;

  midas_time_t start_time;
  midas_time_t end_time;
  std::optional<uint64_t> start_hcycle;
  std::optional<uint64_t> end_hcycle;
  uint64_t end_tcycle = 0;
};

#endif // __SIMULATION_H
